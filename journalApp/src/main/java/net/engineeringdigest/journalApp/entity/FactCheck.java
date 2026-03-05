package net.engineeringdigest.journalApp.entity;

import lombok.*;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;
import java.util.List;

@Document(collection = "fact_checks")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class FactCheck {
    @Id
    private ObjectId id;

    @Indexed // Index this for fast lookups by user
    private ObjectId userId;

    private String rawText;
    private String extractedClaim; // The concise claim from Python

    // Store as List<Double> for simple use, or use MongoDB Atlas Vector Search types
    private List<Double> textEmbedding;

    private String aiVerdict;      // e.g., "TRUE", "FALSE", "MISLEADING"
    private Double confidenceScore; // The cosine similarity result
    private String aiExplanation;
    private List<String> sourceUrls; // Changed to List for multiple sources

    private Date createdAt = new Date();
}
