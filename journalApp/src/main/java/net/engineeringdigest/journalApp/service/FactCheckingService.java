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
import org.springframework.web.util.UriComponentsBuilder;

import java.util.*;

@Service
public class FactCheckingService {
    private static final double RELEVANCE_THRESHOLD = 0.70;  // Snippets below this are noise
    private static final int MAX_SNIPPETS_TO_FETCH = 10;     // Fetch more, filter down
    private static final int MAX_SNIPPETS_TO_LLM = 3;        // LLM only sees top 3 relevant

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
    public void init(){
        this.chatClient=chatClientBuilder
                .defaultSystem("You are an expert, neutral fact-checker. Your job is to analyze claims against provided news snippets").build();


    }

    public FactCheck processAndValidateMessage(ObjectId userId,String rawText){
        String claim=extractClaim(rawText);


        float[] claimEmbedArray = embeddingModel.embed(claim);
        List<Double>claimEmbedding=toDoubleList(claimEmbedArray);

        NewsResult newsResult = fetchNewsSnippets(claim);

        List<ScoredSnippet> relevantSnippets = filterRelevantSnippets(claimEmbedding, newsResult);

        double bestScore=relevantSnippets.isEmpty()?0.0:relevantSnippets.get(0).score();

        List<String> snippetsForLLM=relevantSnippets.stream().map(ScoredSnippet::snippet).toList();

        // Step 7: LLM makes verdict based only on relevant context
        JudgeDecision decision = judgeClaim(claim, snippetsForLLM);

        // Step 8: Collect source URLs only from relevant snippets
        List<String> relevantUrls = relevantSnippets.stream()
                .map(ScoredSnippet::url)
                .toList();

        // Step 9: Build the entity
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

        // Step 10: Save to MongoDB and return
        return factCheckRepository.save(factCheck);
    }

    private List<ScoredSnippet> filterRelevantSnippets(List<Double>claimEmbedding,NewsResult newsResult){
        List<ScoredSnippet> scored = new ArrayList<>();

        for (int i = 0; i < newsResult.snippets().size(); i++) {
            String snippet = newsResult.snippets().get(i);
            String url = newsResult.urls().get(i);

            // Embed each snippet individually
            List<Double> snippetEmbedding = toDoubleList(embeddingModel.embed(snippet));

            // Score it against the claim
            double score = calculateCosineSimilarity(claimEmbedding, snippetEmbedding);

            // Only keep snippets above the generic relevance threshold
            if (score >= RELEVANCE_THRESHOLD) {
                scored.add(new ScoredSnippet(snippet, url, score));
            }
        }

        // Sort by most relevant first, then cap at MAX_SNIPPETS_TO_LLM
        scored.sort(Comparator.comparingDouble(ScoredSnippet::score).reversed());

        return scored.stream()
                .limit(MAX_SNIPPETS_TO_LLM)
                .toList();
    }

    private JudgeDecision judgeClaim(String claim, List<String> relevantSnippets) {

        // Edge case: no relevant news found at all
        if (relevantSnippets.isEmpty()) {
            return new JudgeDecision(
                    "UNVERIFIABLE",
                    "No sufficiently relevant news snippets were found to verify this claim. " +
                            "This could mean the claim is too recent, too niche, or not covered by available sources."
            );
        }

        String newsContext = String.join("\n---\n", relevantSnippets);

        String prompt = String.format(
                "Evaluate the following claim based STRICTLY on the provided news snippets.\n" +
                        "Do NOT use any outside knowledge. If snippets do not clearly support or deny the claim, say MISLEADING.\n\n" +
                        "Claim: %s\n\n" +
                        "News Snippets:\n%s\n\n" +
                        "Respond in raw JSON with exactly two keys:\n" +
                        "- 'verdict': must be exactly one of TRUE, FALSE, MISLEADING, or UNVERIFIABLE\n" +
                        "- 'explanation': a concise explanation referencing the snippets",
                claim, newsContext
        );

        String jsonResponse = chatClient.prompt(prompt).call().content();

        try {
            String cleanJson = jsonResponse
                    .replaceAll("```json", "")
                    .replaceAll("```", "")
                    .trim();
            JsonNode node = objectMapper.readTree(cleanJson);
            return new JudgeDecision(
                    node.path("verdict").asText("MISLEADING"),
                    node.path("explanation").asText("No explanation provided.")
            );
        } catch (Exception e) {
            System.err.println("Error parsing LLM response: " + e.getMessage());
            return new JudgeDecision("MISLEADING", "Failed to parse AI response: " + e.getMessage());
        }
    }

    private double calculateCosineSimilarity(List<Double> vectorA,List<Double>vectorB){
        double dotProduct=0.0;
        double normA=0.0;
        double normB=0.0;
        for (int i = 0; i < vectorA.size(); i++) {
            dotProduct += vectorA.get(i) * vectorB.get(i);
            normA += Math.pow(vectorA.get(i), 2);
            normB += Math.pow(vectorB.get(i), 2);
        }
        if (normA == 0 || normB == 0) return 0.0;
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));

    }

    public String extractClaim(String rawText){
        PromptTemplate promptTemplate=new PromptTemplate(
                "Extract the core, searchable factual claim from the following message. " +
                        "Ignore greetings, conversational fluff, and opinions. Return ONLY the factual claim.\n\n" +
                        "Message: {message}"
        );

        return chatClient.prompt(promptTemplate.create(Map.of("message", rawText)))
                .call()
                .content()
                .trim();
    }

    public NewsResult fetchNewsSnippets(String claim) {
        List<String> snippets = new ArrayList<>();
        List<String> urls = new ArrayList<>();

        try {
            String url = UriComponentsBuilder
                    .fromHttpUrl("https://api.mediastack.com/v1/news")
                    .queryParam("access_key", newsApiKey)  // <-- "access_key" not "apiKey"
                    .queryParam("keywords", claim)          // <-- "keywords" not "q"
                    .queryParam("limit", MAX_SNIPPETS_TO_FETCH)  // <-- "limit" not "pageSize"
                    .queryParam("sort", "popularity")       // <-- "sort" not "sortBy"
                    .queryParam("languages", "en")          // <-- "languages" not "language"
                    .toUriString();

            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);
            JsonNode articles = root.path("data");   // <-- Mediastack returns "data" not "articles"

            for (int i = 0; i < articles.size(); i++) {
                JsonNode article = articles.get(i);
                String title = article.path("title").asText("");
                String description = article.path("description").asText("");
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


    private List<Double> toDoubleList(float[] floatArray) {
        List<Double> doubleList = new ArrayList<>(floatArray.length);
        for (float f : floatArray) {
            doubleList.add((double) f);
        }
        return doubleList;
    }


    private record NewsResult(List<String> snippets, List<String> urls) {}
    private record JudgeDecision(String verdict, String explanation) {}
    private record ScoredSnippet(String snippet, String url, double score) {}
}
