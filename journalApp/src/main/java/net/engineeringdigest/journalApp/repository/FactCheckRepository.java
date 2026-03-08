package net.engineeringdigest.journalApp.repository;

import net.engineeringdigest.journalApp.entity.FactCheck;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface FactCheckRepository extends MongoRepository<FactCheck, ObjectId> {
    List<FactCheck> findByUser(ObjectId userId);
}
