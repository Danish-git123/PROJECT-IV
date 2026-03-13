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

@Service
public class FactCheckingService {
    private static final double RELEVANCE_THRESHOLD = 0.05;
    private static final int MAX_SNIPPETS_TO_FETCH = 10;
    private static final int MAX_SNIPPETS_TO_LLM = 3;

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

    public FactCheck processAndValidateMessage(ObjectId userId, String rawText) {

        ClaimWithDateAndKeywords extracted = extractClaimAndDate(rawText);
        String claim = extracted.claim();
        String dateFrom = extracted.dateFrom();
        String dateTo = extracted.dateTo();
        String searchKeywords = extracted.searchKeywords();

        System.out.println("Extracted claim: " + claim);
        System.out.println("Date frame: " + dateFrom + " to " + dateTo);
        System.out.println("Search keywords: " + searchKeywords);

        float[] claimEmbedArray = embeddingModel.embed(claim);
        List<Double> claimEmbedding = toDoubleList(claimEmbedArray);

        NewsResult newsResult = fetchNewsSnippets(searchKeywords, dateFrom, dateTo);
        System.out.println("Fetched " + newsResult.snippets().size() + " snippets");

        List<ScoredSnippet> relevantSnippets = filterRelevantSnippets(claimEmbedding, newsResult);
        System.out.println("Relevant snippets after filter: " + relevantSnippets.size());

        double bestScore = relevantSnippets.isEmpty() ? 0.0 : relevantSnippets.get(0).score();
        List<String> snippetsForLLM = relevantSnippets.stream().map(ScoredSnippet::snippet).toList();
        List<String> relevantUrls = relevantSnippets.stream().map(ScoredSnippet::url).toList();

        JudgeDecision decision = judgeClaim(claim, snippetsForLLM, dateFrom, dateTo);

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

    /**
     * LLM decides BOTH the claim AND the most appropriate date for news search.
     * No hardcoded keyword rules — LLM uses its own reasoning.
     */
    private ClaimWithDateAndKeywords extractClaimAndDate(String rawText) {
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);

        PromptTemplate promptTemplate = new PromptTemplate(
                "Today's date is {today}.\n\n" +
                        "From the message below, extract the following and return ONLY raw JSON. Note that the message can be about ANY concept (wars, rumors, internal/external country matters, etc.):\n\n" +
                        "1. 'claim': The core verifiable factual claim. MUST BE EXTREMELY SHORT, AT MOST 2 TO 3 WORDS LONG. Do NOT output a full sentence, even if the user message is a paragraph. E.g., 'maduro capture' or 'apple disney'.\n\n" +
                        "2. Time tracking for news search:\n" +
                        "   - 'date_from' (YYYY-MM-DD): The earliest date this claim might have appeared in the news. For recent events, a few days ago. For old resurfaced rumors, it could be months or years ago. If completely timeless or uncertain, return null.\n" +
                        "   - 'date_to' (YYYY-MM-DD): The latest date for the claim, usually {today}. Return null if uncertain.\n\n" +
                        "3. 'search_keywords': A single string with 1 to 2 highly specific keywords from the claim to use for a search engine. DO NOT leave this empty. Give ONLY the most important entity name or key term (e.g., 'Nicolas Maduro' instead of 'Nicolas Maduro capture', or 'Apple Disney' instead of 'Apple Disney acquisition'). We want broad searches to prevent 0 results.\n\n" +
                        "Message: {message}\n\n" +
                        "Respond ONLY with raw JSON. No markdown. No explanation.\n" +
                        "Format example 1: claim is the key, date_from/date_to are YYYY-MM-DD string or null, search_keywords is a string."
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
            String dateTo = node.path("date_to").isNull() ? null : node.path("date_to").asText(null);

            String searchKeywords = node.path("search_keywords").asText("");
            if (searchKeywords.isBlank() && !claim.isBlank()) {
                String cleanClaim = claim.replaceAll("[^a-zA-Z0-9 ]", "").trim();
                String[] words = cleanClaim.split("\\s+");
                searchKeywords = String.join(" ", Arrays.copyOfRange(words, 0, Math.min(words.length, 2)));
            }

            return new ClaimWithDateAndKeywords(claim, dateFrom, dateTo, searchKeywords);
        } catch (Exception e) {
            System.err.println("Failed to parse claim+date JSON: " + e.getMessage());
            return new ClaimWithDateAndKeywords(rawText, null, null, rawText);
        }
    }

    private List<ScoredSnippet> filterRelevantSnippets(List<Double> claimEmbedding, NewsResult newsResult) {
        List<ScoredSnippet> scored = new ArrayList<>();

        for (int i = 0; i < newsResult.snippets().size(); i++) {
            String snippet = newsResult.snippets().get(i);
            String url = newsResult.urls().get(i);

            List<Double> snippetEmbedding = toDoubleList(embeddingModel.embed(snippet));
            double score = calculateCosineSimilarity(claimEmbedding, snippetEmbedding);

            System.out.println("Score " + String.format("%.3f", score) + " -> " + snippet);

            if (score >= RELEVANCE_THRESHOLD) {
                scored.add(new ScoredSnippet(snippet, url, score));
            }
        }

        scored.sort(Comparator.comparingDouble(ScoredSnippet::score).reversed());
        return scored.stream().limit(MAX_SNIPPETS_TO_LLM).toList();
    }

    private JudgeDecision judgeClaim(String claim, List<String> relevantSnippets, String dateFrom, String dateTo) {
        if (relevantSnippets.isEmpty()) {
            String timing = (dateFrom != null && dateTo != null) ? " between " + dateFrom + " and " + dateTo : "";
            return new JudgeDecision(
                    "UNVERIFIABLE",
                    "No sufficiently relevant news snippets were found for this claim" + timing +
                            ". The claim may be too recent, too niche, or not covered by available sources."
            );
        }

        String newsContext = String.join("\n---\n", relevantSnippets);
        // Note: dateFrom/dateTo are intentionally NOT passed to the LLM as a truth condition.
        // The snippets were already filtered by date during the news fetch.
        // Telling the LLM "events must have occurred between X and Y" causes it to mark claims
        // UNVERIFIABLE simply because snippet text does not contain explicit publish dates.

        String prompt = String.format(
                "You are a fact-checker. Evaluate whether the following claim is supported or contradicted by the news snippets below.\n\n" +
                        "IMPORTANT RULES:\n" +
                        "- The snippets were fetched specifically for this claim and are already date-filtered — treat them as current and relevant.\n" +
                        "- Do NOT require explicit dates to appear in the snippet text. Focus only on whether the CONTENT supports or contradicts the claim.\n" +
                        "- Do NOT use any outside knowledge beyond what is in the snippets.\n" +
                        "- Only return UNVERIFIABLE if the snippets are genuinely ambiguous or off-topic, not just because dates are missing.\n\n" +
                        "Claim: %s\n\n" +
                        "News Snippets:\n%s\n\n" +
                        "You MUST respond with ONLY a valid JSON object using double quotes. No markdown, no backticks, no explanation outside the JSON.\n" +
                        "Required format exactly:\n" +
                        "{\"verdict\": \"TRUE\", \"explanation\": \"your explanation here\"}\n\n" +
                        "verdict must be exactly one of: TRUE, FALSE, UNVERIFIABLE\n" +
                        "  TRUE = one or more snippets clearly support the claim\n" +
                        "  FALSE = one or more snippets clearly contradict the claim\n" +
                        "  UNVERIFIABLE = snippets are genuinely ambiguous, off-topic, or insufficient",
                claim, newsContext
        );

        String jsonResponse = chatClient.prompt(prompt).call().content();
        try {
            // Strip markdown fences if present
            String cleanJson = jsonResponse.replaceAll("(?s)```json\\s*", "").replaceAll("```", "").trim();
            // Extract only the JSON object portion (in case of any preamble)
            int start = cleanJson.indexOf('{');
            int end = cleanJson.lastIndexOf('}');
            if (start != -1 && end != -1) {
                cleanJson = cleanJson.substring(start, end + 1);
            }
            // Fallback: replace single-quoted keys/values with double quotes
            // e.g. {'verdict': 'TRUE'} -> {"verdict": "TRUE"}
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

    public NewsResult fetchNewsSnippets(String searchKeywords, String dateFrom, String dateTo) {
        List<String> snippets = new ArrayList<>();
        List<String> urls = new ArrayList<>();

        try {
            // Build URL manually to avoid UriComponentsBuilder double-encoding
            // and ensure param names are passed exactly as GNews expects them.
            // NOTE: Do NOT wrap searchKeywords in quotes — GNews rejects quoted phrases as a syntax error.
            StringBuilder urlBuilder = new StringBuilder("https://gnews.io/api/v4/search");
            urlBuilder.append("?q=").append(java.net.URLEncoder.encode(searchKeywords, java.nio.charset.StandardCharsets.UTF_8));
            urlBuilder.append("&lang=en");
            urlBuilder.append("&max=").append(MAX_SNIPPETS_TO_FETCH);
            urlBuilder.append("&sortby=publishedAt");

            if (dateFrom != null && dateTo != null) {
                try {
                    // validation
                    LocalDate.parse(dateFrom, DateTimeFormatter.ISO_LOCAL_DATE);
                    LocalDate.parse(dateTo, DateTimeFormatter.ISO_LOCAL_DATE);
                    urlBuilder.append("&from=").append(dateFrom).append("T00:00:00Z");
                    urlBuilder.append("&to=").append(dateTo).append("T23:59:59Z");
                    System.out.println("Fetching news from " + dateFrom + " to " + dateTo);
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
            System.out.println("DEBUG API Call: Fetching from -> " + url.replace(newsApiKey, "HIDDEN_KEY"));
            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);
            JsonNode articles = root.path("articles");

            for (int i = 0; i < Math.min(articles.size(), MAX_SNIPPETS_TO_FETCH); i++) {
                JsonNode article = articles.get(i);
                String title = article.path("title").asText("");
                String description = article.path("description").asText("");
                if (description.isBlank()) {
                    description = article.path("content").asText(""); // fallback to content
                }
                String articleUrl = article.path("url").asText("");

                if (!title.isBlank() && !description.isBlank()) {
                    snippets.add(title + " - " + description);
                    urls.add(articleUrl);
                }
            }

        } catch (Exception e) {
            System.err.println("Error fetching news: " + e.getMessage());
        }

        return new NewsResult(snippets, urls);
    }

    private double calculateCosineSimilarity(List<Double> vectorA, List<Double> vectorB) {
        double dotProduct = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < vectorA.size(); i++) {
            dotProduct += vectorA.get(i) * vectorB.get(i);
            normA += Math.pow(vectorA.get(i), 2);
            normB += Math.pow(vectorB.get(i), 2);
        }
        if (normA == 0 || normB == 0) return 0.0;
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private List<Double> toDoubleList(float[] floatArray) {
        List<Double> list = new ArrayList<>(floatArray.length);
        for (float f : floatArray) list.add((double) f);
        return list;
    }

    private record ClaimWithDateAndKeywords(String claim, String dateFrom, String dateTo, String searchKeywords) {}
    private record NewsResult(List<String> snippets, List<String> urls) {}
    private record JudgeDecision(String verdict, String explanation) {}
    private record ScoredSnippet(String snippet, String url, double score) {}
}