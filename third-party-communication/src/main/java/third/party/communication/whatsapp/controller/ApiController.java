package third.party.communication.whatsapp.controller; // Adjust package name if needed

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import third.party.communication.whatsapp.dto.Contact;
import third.party.communication.whatsapp.dto.DefaultResponse;
import third.party.communication.whatsapp.dto.HistoryEntry;
import third.party.communication.whatsapp.dto.SendRequest; // Expects numbers as String
import third.party.communication.whatsapp.service.WhatsappService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays; // Import Arrays
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@CrossOrigin(origins = "http://localhost:3000") // Allow requests from your React app
public class ApiController {

    private static final Logger logger = LoggerFactory.getLogger(ApiController.class);
    private final WhatsappService whatsappService;

    // --- File Paths ---
    private final Path MSG_FILE = Paths.get(System.getProperty("user.dir"), "message.txt");
    private final Path NUMS_FILE = Paths.get(System.getProperty("user.dir"), "numbers.txt");
    private final Path HISTORY_LOG_FILE = Paths.get(System.getProperty("user.dir"), "history.log");
    private final Path CONTACTS_CSV_FILE = Paths.get(System.getProperty("user.dir"), "contacts.csv");

    @Autowired
    public ApiController(WhatsappService whatsappService) {
        this.whatsappService = whatsappService;
    }

    /**
     * API Endpoint to get default message and numbers from files.
     */
    @GetMapping("/get-defaults")
    public ResponseEntity<DefaultResponse> getDefaults() {
        logger.info("Received GET request for /get-defaults");
        try {
            String msg = readFileOrDefault(MSG_FILE, "Default message if file not found.");
            // Read numbers.txt content for the textarea default
            String nums = readFileOrDefault(NUMS_FILE, ""); // Use empty string if not found
            return ResponseEntity.ok(new DefaultResponse(msg, nums));
        } catch (Exception e) {
            logger.error("Error processing /get-defaults: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new DefaultResponse("Error loading message.", "Error loading numbers."));
        }
    }

    /**
     * API Endpoint to get contacts from CSV (Optional feature)
     */
    @GetMapping("/get-contacts")
    public ResponseEntity<List<Contact>> getContacts() {
        logger.info("Received GET request for /get-contacts");
        List<Contact> contacts = new ArrayList<>();
        if (!Files.exists(CONTACTS_CSV_FILE)) {
            logger.warn("Contacts file not found: {}", CONTACTS_CSV_FILE.toAbsolutePath());
            return ResponseEntity.ok(contacts); // Return empty list
        }
        try (Stream<String> lines = Files.lines(CONTACTS_CSV_FILE, StandardCharsets.UTF_8)) {
            contacts = lines
                    .skip(1) // Skip header if present
                    .map(line -> line.split(",")) // Split by comma
                    .filter(parts -> parts.length >= 2 && !parts[0].trim().isEmpty() && !parts[1].trim().isEmpty())
                    .map(parts -> new Contact(parts[0].trim(), parts[1].trim()))
                    .collect(Collectors.toList());
            logger.info("Successfully read {} contacts from {}", contacts.size(), CONTACTS_CSV_FILE.getFileName());
            return ResponseEntity.ok(contacts);
        } catch (IOException e) {
            logger.error("Error reading contacts file {}: {}", CONTACTS_CSV_FILE.toAbsolutePath(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Collections.emptyList());
        } catch (Exception e) {
            logger.error("Unexpected error processing contacts file {}: {}", CONTACTS_CSV_FILE.toAbsolutePath(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Collections.emptyList());
        }
    }

    /**
     * API Endpoint to get message history from the log file.
     */
    @GetMapping("/get-history")
    public ResponseEntity<List<HistoryEntry>> getHistory() {
        logger.info("Received GET request for /get-history");
        if (!Files.exists(HISTORY_LOG_FILE)) {
            logger.warn("History file not found: {}", HISTORY_LOG_FILE);
            return ResponseEntity.ok(Collections.emptyList());
        }
        try {
            List<String> lines = Files.readAllLines(HISTORY_LOG_FILE, StandardCharsets.UTF_8);
            Collections.reverse(lines); // Show newest first
            List<HistoryEntry> history = lines.stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .map(line -> {
                        String[] parts = line.split(" \\| ", 4);
                        if (parts.length == 4) {
                            return new HistoryEntry(parts[0], parts[1], parts[2], parts[3]);
                        } else {
                            logger.warn("Skipping malformed history line: {}", line);
                            return null;
                        }
                    })
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toList());
            return ResponseEntity.ok(history);
        } catch (IOException e) {
            logger.error("Error reading history log file '{}': {}", HISTORY_LOG_FILE, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Collections.emptyList());
        }
    }

    /**
     * API Endpoint to start the message sending script asynchronously.
     * Receives numbers as a String, splits into List before calling service.
     */
    /**
     * API Endpoint to start the message sending script asynchronously.
     * Receives numbers as a String, splits into List before calling service.
     */
    @PostMapping("/run-script")
    public ResponseEntity<Map<String, String>> runScript(@RequestBody SendRequest request) { // SendRequest expects String numbers
        logger.info("Received POST request for /run-script");
        String msgContent = request.getMessage();
        String numsContent = request.getNumbers(); // <-- Receive as String

        // --- FIX: Split the numbers string here ---
        List<String> numbersList = null;
        if (numsContent != null && !numsContent.trim().isEmpty()) {
            numbersList = Arrays.stream(numsContent.split("\\r?\\n")) // Split by newline
                    .map(String::trim)
                    .filter(s -> !s.isEmpty() && s.matches("[0-9]+")) // Keep only non-empty digit strings
                    .collect(Collectors.toList());
        }
        // ------------------------------------------

        // Basic validation
        if (msgContent == null || msgContent.trim().isEmpty()) {
            logger.warn("Received invalid request for /run-script: Message empty.");
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Message field cannot be empty."));
        }
        // --- FIX: Check the split list ---
        if (numbersList == null || numbersList.isEmpty()) {
            logger.warn("Received invalid request for /run-script: No valid numbers found in input.");
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "No valid phone numbers entered."));
        }
        // ---------------------------------

        // Save message back to file
        try {
            logger.debug("Saving message content to {}", MSG_FILE);
            Files.writeString(MSG_FILE, msgContent, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            logger.warn("Could not save message text to file before starting script: {}", e.getMessage());
        }

        // Start the Selenium logic in a new thread
        // --- FIX: Pass the created numbersList ---
        logger.info("Triggering async Selenium send logic for {} numbers...", numbersList.size());
        whatsappService.runSeleniumLogic(msgContent, numbersList); // Pass the List<String>
        // ----------------------------------------

        // Respond immediately
        return ResponseEntity.ok(Map.of("status", "success", "message", "Script started! Check application logs for progress."));
    }

    /**
     * API ENDPOINT FOR DELETION
     * Receives numbers as a String, splits into List before calling service.
     */
    @PostMapping("/delete-last-message")
    public ResponseEntity<Map<String, String>> deleteLastMessage(@RequestBody SendRequest request) { // SendRequest expects String numbers
        logger.info("Received POST request for /delete-last-message");
        String numsContent = request.getNumbers(); // <-- Receive as String

        // --- FIX: Split the numbers string here ---
        List<String> numbersList = null;
        if (numsContent != null && !numsContent.trim().isEmpty()) {
            numbersList = Arrays.stream(numsContent.split("\\r?\\n")) // Split by newline
                    .map(String::trim)
                    .filter(s -> !s.isEmpty() && s.matches("[0-9]+")) // Keep only non-empty digit strings
                    .collect(Collectors.toList());
        }
        // ------------------------------------------

        // --- FIX: Check the split list ---
        if (numbersList == null || numbersList.isEmpty()) {
            logger.warn("Received invalid request for /delete-last-message: No valid numbers found in input.");
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "No valid phone numbers entered for deletion."));
        }
        // ---------------------------------

        try {
            // Start the Selenium delete logic in a new thread
            // --- FIX: Pass the created numbersList ---
            logger.info("Triggering async Selenium delete logic for {} numbers...", numbersList.size());
            whatsappService.runSeleniumDeleteLogic(numbersList); // Pass the List<String>
            // ----------------------------------------

            // Respond immediately
            return ResponseEntity.ok(Map.of("status", "success", "message", "Delete script started! Check application logs for progress."));

        } catch (Exception e) {
            logger.error("Error initiating /delete-last-message: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("status", "error", "message", "An internal server error occurred while starting delete script."));
        }
    }


    /**
     * Helper Method to read files
     */
    private String readFileOrDefault(Path path, String defaultValue) {
        try {
            if (Files.exists(path)) {
                logger.debug("Reading file: {}", path);
                return Files.readString(path, StandardCharsets.UTF_8);
            } else {
                logger.warn("File not found: {}. Using default value.", path.toAbsolutePath());
            }
        } catch (IOException e) {
            logger.error("Error reading file {}: {}", path.toAbsolutePath(), e.getMessage());
        }
        return defaultValue;
    }
}