package net.engineeringdigest.journalApp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import net.engineeringdigest.journalApp.entity.FactCheck;
import net.engineeringdigest.journalApp.repository.FactCheckRepository;
import org.bson.types.ObjectId;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class FactCheckingService {

    // ── Thresholds & limits ──────────────────────────────────────────────────
    private static final double RELEVANCE_THRESHOLD = 0.05;
    private static final int    MAX_SNIPPETS_TO_FETCH = 10;
    private static final int    MAX_SNIPPETS_TO_LLM   = 4; // increased to give LLM more evidence

    // ── Multi-factor ranking weights ─────────────────────────────────────────
    // These 4 weights must sum to 1.0
    private static final double W_SIMILARITY = 0.45; // semantic closeness to claim
    private static final double W_TRUST      = 0.25; // how reputable is the source
    private static final double W_RECENCY    = 0.20; // how recent is the article
    private static final double W_STANCE     = 0.10; // does snippet take a clear position

    // ── Trusted news domains → trust score 1.0 ──────────────────────────────
    // Unknown/blog domains get 0.3 by default
    private static final Set<String> TRUSTED_DOMAINS = Set.of(
            "reuters.com", "bbc.com", "bbc.co.uk", "apnews.com", "theguardian.com",
            "nytimes.com", "washingtonpost.com", "bloomberg.com", "ft.com",
            "economist.com", "npr.org", "pbs.org", "cnn.com", "nbcnews.com",
            "abcnews.go.com", "cbsnews.com", "politico.com", "thehill.com",
            "foreignpolicy.com", "aljazeera.com", "dw.com", "france24.com",
            "hindustantimes.com", "thehindu.com", "ndtv.com", "timesofindia.com",
            "indiatoday.in", "news18.com", "scroll.in", "thewire.in"
    );

    @Autowired
    private ChatClient.Builder chatClientBuilder;
    private ChatClient chatClient;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${NEWS_API_KEY}")
    private String newsApiKey;

    @Autowired
    private FactCheckRepository factCheckRepository;

    @PostConstruct
    public void init() {
        this.chatClient = chatClientBuilder
                .defaultSystem("You are an expert, neutral fact-checker. Your job is to analyze claims against provided news snippets. You cover all domains: wars, internal/external matters of countries, rumors, pop culture, science, economics, and all other concepts.")
                .build();
    }

    // ════════════════════════════════════════════════════════════════════════
    // PUBLIC ENTRY POINT
    // ════════════════════════════════════════════════════════════════════════

    public FactCheck processAndValidateMessage(ObjectId userId, String rawText) {

        // Step 1 — Extract claim, date range, search keywords via LLM
        ClaimWithDateAndKeywords extracted = extractClaimAndDate(rawText);
        String claim          = extracted.claim();
        String dateFrom       = extracted.dateFrom();
        String dateTo         = extracted.dateTo();
        String searchKeywords = extracted.searchKeywords();

        System.out.println("Extracted claim:   " + claim);
        System.out.println("Date frame:        " + dateFrom + " to " + dateTo);
        System.out.println("Search keywords:   " + searchKeywords);

        // Step 2 — Embed the claim for semantic similarity comparisons
        List<Double> claimEmbedding = toDoubleList(embeddingModel.embed(claim));

        String extraKeywords = extracted.extraKeywords();

        // Step 3 — Fetch raw news snippets from GNews
        // Primary search with main keywords
        NewsResult newsResult = fetchNewsSnippets(searchKeywords, dateFrom, dateTo);
        System.out.println("Fetched " + newsResult.snippets().size() + " snippets (primary search)");

        // Secondary search with extra keywords (different angle) — merge results
        // This avoids getting only tangential articles when primary keywords are too broad
        if (extraKeywords != null && !extraKeywords.isBlank() && newsResult.snippets().size() < 5) {
            System.out.println("Primary search returned few results — running secondary search: " + extraKeywords);
            NewsResult secondResult = fetchNewsSnippets(extraKeywords, dateFrom, dateTo);
            System.out.println("Fetched " + secondResult.snippets().size() + " snippets (secondary search)");
            // Merge both results
            List<String> mergedSnippets = new ArrayList<>(newsResult.snippets());
            List<String> mergedUrls = new ArrayList<>(newsResult.urls());
            List<String> mergedDates = new ArrayList<>(newsResult.publishedDates());
            mergedSnippets.addAll(secondResult.snippets());
            mergedUrls.addAll(secondResult.urls());
            mergedDates.addAll(secondResult.publishedDates());
            newsResult = new NewsResult(mergedSnippets, mergedUrls, mergedDates);
            System.out.println("Total after merge: " + newsResult.snippets().size() + " snippets");
        }

        // Step 4 — Multi-factor ranking + domain diversity filter
        List<ScoredSnippet> rankedSnippets = rankAndFilterSnippets(claimEmbedding, newsResult, dateFrom);
        System.out.println("Ranked snippets after filter: " + rankedSnippets.size());
        rankedSnippets.forEach(s -> System.out.printf(
                "  finalScore=%.3f sim=%.3f trust=%.2f recency=%.2f stance=%.2f → %s%n",
                s.finalScore(), s.similarity(), s.trustScore(), s.recencyScore(), s.stanceScore(),
                s.snippet()));

        // Step 5 — Build stance-annotated context for LLM
        double bestScore       = rankedSnippets.isEmpty() ? 0.0 : rankedSnippets.get(0).finalScore();
        List<String> snippetsForLLM = rankedSnippets.stream().map(ScoredSnippet::snippet).toList();
        List<String> stancesForLLM  = rankedSnippets.stream().map(ScoredSnippet::stance).toList();
        List<String> relevantUrls   = rankedSnippets.stream().map(ScoredSnippet::url).toList();

        // Step 6 — LLM verdict with stance-aware prompt
        // Pass rawText so LLM has full user context, not just the 2-3 word extracted claim
        JudgeDecision decision = judgeClaim(claim, rawText, snippetsForLLM, stancesForLLM);

        // Step 7 — Persist and return
        FactCheck factCheck = FactCheck.builder()
                .userId(userId)
                .rawText(rawText)
                .extractedClaim(claim)
                .textEmbedding(claimEmbedding)
                .aiVerdict(decision.verdict())
                .confidenceScore(bestScore)
                .aiExplanation(decision.explanation())
                .sourceUrls(relevantUrls)
                .createdAt(new Date())
                .build();

        return factCheckRepository.save(factCheck);
    }

    // ════════════════════════════════════════════════════════════════════════
    // STEP 1 — CLAIM + DATE EXTRACTION
    // ════════════════════════════════════════════════════════════════════════

    private ClaimWithDateAndKeywords extractClaimAndDate(String rawText) {
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);

        PromptTemplate promptTemplate = new PromptTemplate(
                "Today's date is {today}.\n\n" +
                        "From the message below, extract the following and return ONLY raw JSON.\n\n" +
                        "1. \"claim\": The core verifiable factual claim. MUST BE EXTREMELY SHORT, 2-3 WORDS MAX.\n" +
                        "   E.g., 'maduro capture' or 'apple disney'.\n\n" +
                        "2. Date range for news search — think carefully about WHEN this event actually happened:\n" +
                        "   - \"date_from\" (YYYY-MM-DD): The date the event or rumor ORIGINALLY occurred or started.\n" +
                        "     Examples:\n" +
                        "       - Claim about 2020 US election → date_from: 2020-11-01\n" +
                        "       - Claim about COVID origin → date_from: 2019-12-01\n" +
                        "       - Claim about recent news (last few days) → date_from: a few days before {today}\n" +
                        "       - Claim with no time reference → date_from: null\n" +
                        "   - \"date_to\" (YYYY-MM-DD): The end of the relevant news window.\n" +
                        "     - If the claim is about an ongoing or recent situation → use {today}\n" +
                        "     - If the claim is about a past completed event → use a date shortly after the event ended\n" +
                        "     - If the claim is about a resurfaced old rumor → use {today} so recent debunks are included\n" +
                        "     - If uncertain → null\n\n" +
                        "   IMPORTANT: Do NOT default both dates to today. Think about when the event actually happened.\n\n" +
                        "3. \"search_keywords\": 1-2 highly specific keywords for a news search engine.\n" +
                        "   Broad enough to get results. E.g., 'Nicolas Maduro' not 'Nicolas Maduro capture'.\n\n" +
                        "4. \"extra_keywords\": A second alternative search string (different angle) to find more articles.\n" +
                        "   E.g., if search_keywords is 'Nicolas Maduro', extra_keywords could be 'Venezuela president jailed'.\n" +
                        "   This helps when the first search returns tangential results. Never leave empty.\n\n" +
                        "Message: {message}\n\n" +
                        "Respond ONLY with raw JSON using double quotes. No markdown. No explanation."
        );

        String jsonResponse = chatClient
                .prompt(promptTemplate.create(Map.of("today", today, "message", rawText)))
                .call()
                .content()
                .trim();

        try {
            String clean = jsonResponse.replaceAll("```json", "").replaceAll("```", "").trim();
            JsonNode node = objectMapper.readTree(clean);
            String claim = node.path("claim").asText("").trim();
            String dateFrom = node.path("date_from").isNull() ? null : node.path("date_from").asText(null);
            String dateTo   = node.path("date_to").isNull()   ? null : node.path("date_to").asText(null);

            String searchKeywords = node.path("search_keywords").asText("");
            if (searchKeywords.isBlank() && !claim.isBlank()) {
                String[] words = claim.replaceAll("[^a-zA-Z0-9 ]", "").trim().split("\\s+");
                searchKeywords = String.join(" ", Arrays.copyOfRange(words, 0, Math.min(words.length, 2)));
            }

            String extraKeywords = node.path("extra_keywords").asText("");
            if (extraKeywords.isBlank()) extraKeywords = null;

            return new ClaimWithDateAndKeywords(claim, dateFrom, dateTo, searchKeywords, extraKeywords);
        } catch (Exception e) {
            System.err.println("Failed to parse claim+date JSON: " + e.getMessage());
            return new ClaimWithDateAndKeywords(rawText, null, null, rawText, null);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // STEP 4 — MULTI-FACTOR RANKING + DOMAIN DIVERSITY
    // ════════════════════════════════════════════════════════════════════════

    /**
     * OLD approach: rank only by cosine similarity
     *   → biased toward duplicate sources, ignores recency, ignores source quality
     *
     * NEW approach: multi-factor score
     *   finalScore = 0.45*similarity + 0.25*trust + 0.20*recency + 0.10*stance
     *
     * Also applies domain diversity: max 1 snippet per domain
     * to prevent one biased blog dominating the LLM context.
     */
    private List<ScoredSnippet> rankAndFilterSnippets(
            List<Double> claimEmbedding,
            NewsResult newsResult,
            String dateFrom) {

        List<ScoredSnippet> scored = new ArrayList<>();

        for (int i = 0; i < newsResult.snippets().size(); i++) {
            String snippet    = newsResult.snippets().get(i);
            String url        = newsResult.urls().get(i);
            String publishedAt = newsResult.publishedDates().get(i);

            // ── Factor 1: Semantic similarity (cosine) ───────────────────
            List<Double> snippetEmbedding = toDoubleList(embeddingModel.embed(snippet));
            double similarity = calculateCosineSimilarity(claimEmbedding, snippetEmbedding);

            if (similarity < RELEVANCE_THRESHOLD) continue; // skip irrelevant

            // ── Factor 2: Source trust score ─────────────────────────────
            double trustScore = computeTrustScore(url);

            // ── Factor 3: Recency score ───────────────────────────────────
            double recencyScore = computeRecencyScore(publishedAt, dateFrom);

            // ── Factor 4: Stance score ────────────────────────────────────
            // Snippets that clearly SUPPORT or REFUTE are more useful than neutral ones
            StanceResult stance = detectStance(snippet);
            double stanceScore  = stance.score();

            // ── Final weighted score ──────────────────────────────────────
            double finalScore = (W_SIMILARITY * similarity)
                    + (W_TRUST      * trustScore)
                    + (W_RECENCY    * recencyScore)
                    + (W_STANCE     * stanceScore);

            scored.add(new ScoredSnippet(
                    snippet, url, similarity, trustScore, recencyScore,
                    stanceScore, finalScore, stance.label()));
        }

        // Sort by finalScore descending
        scored.sort(Comparator.comparingDouble(ScoredSnippet::finalScore).reversed());

        // ── Domain diversity filter ───────────────────────────────────────
        // Max 1 snippet per domain to prevent one biased blog dominating
        Set<String> usedDomains = new HashSet<>();
        List<ScoredSnippet> diverse = new ArrayList<>();

        for (ScoredSnippet s : scored) {
            String domain = extractDomain(s.url());
            if (!usedDomains.contains(domain)) {
                usedDomains.add(domain);
                diverse.add(s);
                if (diverse.size() >= MAX_SNIPPETS_TO_LLM) break;
            }
        }

        return diverse;
    }

    // ── Trust score ──────────────────────────────────────────────────────────
    private double computeTrustScore(String url) {
        String domain = extractDomain(url);
        // Exact match
        if (TRUSTED_DOMAINS.contains(domain)) return 1.0;
        // Partial match (e.g., regional subdomains like 'india.reuters.com')
        for (String trusted : TRUSTED_DOMAINS) {
            if (domain.endsWith("." + trusted) || domain.contains(trusted)) return 0.85;
        }
        // Unknown source — low trust but not zero (could still be relevant)
        return 0.3;
    }

    // ── Recency score ────────────────────────────────────────────────────────
    // Score 1.0 = published today, decays linearly, 0.1 = 30+ days old
    private double computeRecencyScore(String publishedAt, String dateFrom) {
        if (publishedAt == null || publishedAt.isBlank()) return 0.5; // unknown → neutral

        try {
            LocalDate pubDate  = LocalDate.parse(publishedAt.substring(0, 10), DateTimeFormatter.ISO_LOCAL_DATE);
            LocalDate today    = LocalDate.now();
            long daysOld       = java.time.temporal.ChronoUnit.DAYS.between(pubDate, today);

            if (daysOld <= 0)  return 1.0;
            if (daysOld >= 30) return 0.1;
            // Linear decay from 1.0 (today) to 0.1 (30 days ago)
            return 1.0 - (daysOld / 30.0 * 0.9);
        } catch (Exception e) {
            return 0.5;
        }
    }

    // ── Stance detection ─────────────────────────────────────────────────────
    /**
     * Lightweight keyword-based stance detection.
     * Avoids extra LLM call — fast and good enough for ranking purposes.
     * Full LLM stance reasoning happens inside judgeClaim prompt.
     *
     * SUPPORTS keywords  → snippet clearly supports the claim
     * REFUTES keywords   → snippet clearly contradicts the claim
     * NEUTRAL            → snippet just mentions the topic
     */
    private StanceResult detectStance(String snippet) {
        String lower = snippet.toLowerCase();

        // Refutation signals
        boolean refutes = lower.contains("deny") || lower.contains("denied") ||
                lower.contains("false") || lower.contains("debunk") ||
                lower.contains("no evidence") || lower.contains("misleading") ||
                lower.contains("misinformation") || lower.contains("rumor") ||
                lower.contains("rumour") || lower.contains("contradict") ||
                lower.contains("incorrect") || lower.contains("not true") ||
                lower.contains("disproven") || lower.contains("hoax") ||
                lower.contains("fake") || lower.contains("dispute") ||
                lower.contains("refute");

        // Support signals
        boolean supports = lower.contains("confirm") || lower.contains("confirmed") ||
                lower.contains("verified") || lower.contains("officially") ||
                lower.contains("announced") || lower.contains("according to") ||
                lower.contains("report") || lower.contains("reported") ||
                lower.contains("evidence shows") || lower.contains("study shows") ||
                lower.contains("data shows") || lower.contains("killed") ||
                lower.contains("captured") || lower.contains("arrested");

        if (refutes && !supports) return new StanceResult("REFUTES", 1.0);
        if (supports && !refutes) return new StanceResult("SUPPORTS", 1.0);
        if (refutes)              return new StanceResult("MIXED", 0.6);
        if (supports)             return new StanceResult("SUPPORTS", 0.7);
        return                         new StanceResult("NEUTRAL", 0.3);
        // NEUTRAL gets lower score — less useful for verdict
    }

    // ── Domain extractor ─────────────────────────────────────────────────────
    private String extractDomain(String url) {
        try {
            String host = new java.net.URL(url).getHost();
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) {
            return url;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // STEP 6 — STANCE-AWARE LLM VERDICT
    // ════════════════════════════════════════════════════════════════════════

    private JudgeDecision judgeClaim(String claim, String rawText, List<String> snippets, List<String> stances) {
        if (snippets.isEmpty()) {
            return new JudgeDecision(
                    "UNVERIFIABLE",
                    "No sufficiently relevant news snippets were found for this claim. " +
                            "The claim may be too recent, too niche, or not covered by available sources."
            );
        }

        // Build stance-annotated context
        // Each snippet is prefixed with its detected stance so LLM can reason better
        StringBuilder contextBuilder = new StringBuilder();
        for (int i = 0; i < snippets.size(); i++) {
            String stanceLabel = i < stances.size() ? stances.get(i) : "NEUTRAL";
            contextBuilder.append("[Snippet ").append(i + 1).append(" — Stance: ").append(stanceLabel).append("]\n");
            contextBuilder.append(snippets.get(i)).append("\n\n");
        }

        String prompt = String.format(
                "You are a fact-checker. Evaluate whether the following claim is TRUE, FALSE, or UNVERIFIABLE " +
                        "based ONLY on the provided news snippets.\n\n" +

                        "Each snippet has a pre-detected stance label:\n" +
                        "  SUPPORTS = snippet appears to support the claim\n" +
                        "  REFUTES  = snippet appears to contradict the claim\n" +
                        "  NEUTRAL  = snippet mentions the topic without clear position\n" +
                        "  MIXED    = snippet contains both supporting and refuting signals\n\n" +

                        "RULES:\n" +
                        "- Use stance labels as hints, but reason through the content yourself.\n" +
                        "- If multiple REFUTES snippets exist and few SUPPORTS → lean FALSE.\n" +
                        "- If multiple SUPPORTS snippets exist and few REFUTES → lean TRUE.\n" +
                        "- If stances conflict or are all NEUTRAL → return UNVERIFIABLE.\n" +
                        "- Do NOT use outside knowledge. Judge ONLY from the snippets.\n" +
                        "- Do NOT treat absence of dates as a reason for UNVERIFIABLE.\n\n" +

                        "Original user message (full context): %s\n" +
                        "Extracted core claim: %s\n\n" +
                        "Evidence:\n%s\n" +
                        "You MUST respond with ONLY a valid JSON object using double quotes.\n" +
                        "Required format: {\"verdict\": \"TRUE\", \"explanation\": \"your explanation here\"}\n" +
                        "verdict must be exactly: TRUE, FALSE, or UNVERIFIABLE",
                rawText, claim, contextBuilder.toString()
        );

        String jsonResponse = chatClient.prompt(prompt).call().content();
        try {
            String cleanJson = jsonResponse.replaceAll("(?s)```json\\s*", "").replaceAll("```", "").trim();
            int start = cleanJson.indexOf('{');
            int end   = cleanJson.lastIndexOf('}');
            if (start != -1 && end != -1) cleanJson = cleanJson.substring(start, end + 1);

            // Fallback single-quote fix
            cleanJson = cleanJson.replaceAll("'([^']*)'\\s*:", "\"$1\":");
            cleanJson = cleanJson.replaceAll(":\\s*'([^']*)'", ": \"$1\"");

            JsonNode node = objectMapper.readTree(cleanJson);
            return new JudgeDecision(
                    node.path("verdict").asText("UNVERIFIABLE"),
                    node.path("explanation").asText("No explanation provided.")
            );
        } catch (Exception e) {
            System.err.println("Failed to parse judge JSON: " + e.getMessage() + " | Raw: " + jsonResponse);
            return new JudgeDecision("UNVERIFIABLE", "Failed to parse AI response: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // NEWS FETCH — now also captures publishedAt date
    // ════════════════════════════════════════════════════════════════════════

    public NewsResult fetchNewsSnippets(String searchKeywords, String dateFrom, String dateTo) {
        List<String> snippets      = new ArrayList<>();
        List<String> urls          = new ArrayList<>();
        List<String> publishedDates = new ArrayList<>();

        try {
            StringBuilder urlBuilder = new StringBuilder("https://gnews.io/api/v4/search");
            urlBuilder.append("?q=").append(java.net.URLEncoder.encode(searchKeywords, java.nio.charset.StandardCharsets.UTF_8));
            urlBuilder.append("&lang=en");
            urlBuilder.append("&max=").append(MAX_SNIPPETS_TO_FETCH);
            urlBuilder.append("&sortby=publishedAt");

            if (dateFrom != null && dateTo != null) {
                try {
                    LocalDate parsedFrom = LocalDate.parse(dateFrom, DateTimeFormatter.ISO_LOCAL_DATE);
                    LocalDate parsedTo   = LocalDate.parse(dateTo,   DateTimeFormatter.ISO_LOCAL_DATE);

                    // Safety net: if LLM collapsed both dates to same day (e.g. today),
                    // widen the window — single-day searches almost always return 0 results
                    long daysBetween = java.time.temporal.ChronoUnit.DAYS.between(parsedFrom, parsedTo);
                    if (daysBetween <= 1) {
                        parsedFrom = parsedFrom.minusDays(7);
                        parsedTo   = parsedTo.plusDays(7).isAfter(LocalDate.now())
                                ? LocalDate.now() : parsedTo.plusDays(7);
                        System.out.println("Date window too narrow — auto-widened to: " + parsedFrom + " to " + parsedTo);
                    }

                    urlBuilder.append("&from=").append(parsedFrom).append("T00:00:00Z");
                    urlBuilder.append("&to=").append(parsedTo).append("T23:59:59Z");
                    System.out.println("Fetching news from " + parsedFrom + " to " + parsedTo);
                } catch (Exception ignored) {
                    System.out.println("Date parse failed, fetching without date filter");
                }
            } else if (dateFrom != null) {
                try {
                    LocalDate.parse(dateFrom, DateTimeFormatter.ISO_LOCAL_DATE);
                    urlBuilder.append("&from=").append(dateFrom).append("T00:00:00Z");
                    urlBuilder.append("&to=").append(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)).append("T23:59:59Z");
                } catch (Exception ignored) {}
            } else {
                System.out.println("No exact date frame — fetching latest relevant news");
            }

            urlBuilder.append("&apikey=").append(newsApiKey);
            String url = urlBuilder.toString();
            System.out.println("DEBUG API Call: " + url.replace(newsApiKey, "HIDDEN_KEY"));

            String response = restTemplate.getForObject(url, String.class);
            JsonNode root     = objectMapper.readTree(response);
            JsonNode articles = root.path("articles");

            for (int i = 0; i < Math.min(articles.size(), MAX_SNIPPETS_TO_FETCH); i++) {
                JsonNode article    = articles.get(i);
                String title        = article.path("title").asText("");
                String description  = article.path("description").asText("");
                if (description.isBlank()) description = article.path("content").asText("");
                String articleUrl   = article.path("url").asText("");
                String publishedAt  = article.path("publishedAt").asText(""); // NEW — capture date

                if (!title.isBlank() && !description.isBlank()) {
                    snippets.add(title + " - " + description);
                    urls.add(articleUrl);
                    publishedDates.add(publishedAt);
                }
            }
        } catch (Exception e) {
            System.err.println("Error fetching news: " + e.getMessage());
        }

        return new NewsResult(snippets, urls, publishedDates);
    }

    // ════════════════════════════════════════════════════════════════════════
    // UTILITIES
    // ════════════════════════════════════════════════════════════════════════

    private double calculateCosineSimilarity(List<Double> vectorA, List<Double> vectorB) {
        double dotProduct = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < vectorA.size(); i++) {
            dotProduct += vectorA.get(i) * vectorB.get(i);
            normA      += Math.pow(vectorA.get(i), 2);
            normB      += Math.pow(vectorB.get(i), 2);
        }
        if (normA == 0 || normB == 0) return 0.0;
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private List<Double> toDoubleList(float[] floatArray) {
        List<Double> list = new ArrayList<>(floatArray.length);
        for (float f : floatArray) list.add((double) f);
        return list;
    }

    // ════════════════════════════════════════════════════════════════════════
    // RECORDS
    // ════════════════════════════════════════════════════════════════════════

    private record ClaimWithDateAndKeywords(
            String claim, String dateFrom, String dateTo, String searchKeywords, String extraKeywords) {}

    // Updated: now carries publishedDates list for recency scoring
    private record NewsResult(
            List<String> snippets, List<String> urls, List<String> publishedDates) {}

    private record JudgeDecision(String verdict, String explanation) {}

    private record StanceResult(String label, double score) {}

    // Updated: multi-factor fields instead of single score
    private record ScoredSnippet(
            String snippet,
            String url,
            double similarity,
            double trustScore,
            double recencyScore,
            double stanceScore,
            double finalScore,
            String stance       // "SUPPORTS" | "REFUTES" | "NEUTRAL" | "MIXED"
    ) {}
}