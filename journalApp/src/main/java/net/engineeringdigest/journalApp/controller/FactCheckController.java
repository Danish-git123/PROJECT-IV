package net.engineeringdigest.journalApp.controller;

import net.engineeringdigest.journalApp.entity.FactCheck;
import net.engineeringdigest.journalApp.service.FactCheckingService;
import net.engineeringdigest.journalApp.utilis.JwtUtil;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/fact-check")
public class FactCheckController {

    @Autowired
    private FactCheckingService factCheckingService;

    @PostMapping
    public ResponseEntity<?> factCheck(@RequestBody String rawText) {
        try {
            Authentication authentication = SecurityContextHolder
                    .getContext().getAuthentication();

            // userId was stored in details by JwtFilter
            String userId = (String) authentication.getDetails();

            FactCheck result = factCheckingService.processAndValidateMessage(
                    new ObjectId(userId), rawText
            );
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error: " + e.getMessage());
        }
    }
}