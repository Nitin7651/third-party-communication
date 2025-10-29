package third.party.communication.whatsapp.controller; // Adjust package name if needed

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import third.party.communication.whatsapp.dto.Contact;
import third.party.communication.whatsapp.dto.DefaultResponse;
import third.party.communication.whatsapp.dto.HistoryEntry;
import third.party.communication.whatsapp.dto.SendRequest; // Expects List<String> numbers
import third.party.communication.whatsapp.service.ContactService; // Import ContactService
import third.party.communication.whatsapp.service.WhatsappService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@RestController
@CrossOrigin(origins = "http://localhost:3000") // Allow requests from your React app
public class ApiController {

    private static final Logger logger = LoggerFactory.getLogger(ApiController.class);
    private final WhatsappService whatsappService;
    private final ContactService contactService; // Inject ContactService

    // --- File Paths ---
    // Paths are now primarily managed within their respective services
    private final Path MSG_FILE = Paths.get(System.getProperty("user.dir"), "message.txt");
    private final Path HISTORY_LOG_FILE = Paths.get(System.getProperty("user.dir"), "history.log");


    @Autowired
    public ApiController(WhatsappService whatsappService, ContactService contactService) { // Inject ContactService
        this.whatsappService = whatsappService;
        this.contactService = contactService; // Assign injected service
    }

    /**
     * API Endpoint to get default message from file.
     */
    @GetMapping("/get-defaults")
    public ResponseEntity<DefaultResponse> getDefaults() {
        logger.info("Received GET request for /get-defaults");
        try {
            String msg = readFileOrDefault(MSG_FILE, "Default message if file not found.");
            // DefaultResponse no longer includes numbers field
            return ResponseEntity.ok(new DefaultResponse(msg, null)); // Pass null for numbers
        } catch (Exception e) {
            logger.error("Error processing /get-defaults: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new DefaultResponse("Error loading message.", null));
        }
    }

    /**
     * API Endpoint to get contacts using ContactService.
     */
    @GetMapping("/get-contacts")
    public ResponseEntity<List<Contact>> getContacts() {
        logger.info("Received GET request for /get-contacts");
        try {
            List<Contact> contacts = contactService.loadContactsFromCsv();
            return ResponseEntity.ok(contacts);
        } catch (Exception e) {
            logger.error("Unexpected error in /get-contacts controller: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Collections.emptyList());
        }
    }

    /**
     * NEW: API Endpoint to add a contact to the CSV via ContactService.
     */
    @PostMapping("/add-contact")
    public ResponseEntity<Map<String, Object>> addContact(@RequestBody Contact newContact) {
        logger.info("Received POST request for /add-contact: Name={}, Number={}", newContact.getName(), newContact.getNumber());
        // Basic validation moved to service, but controller can do initial checks
        if (newContact == null || newContact.getNumber() == null) {
            logger.warn("Received invalid contact data in request body.");
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Invalid contact data provided."));
        }
        // Clean number before passing to service (or let service handle it)
        String cleanedNumber = newContact.getNumber().replaceAll("\\D", "");
        if (!cleanedNumber.matches("\\d{10,}")) { // Require at least 10 digits
            logger.warn("Invalid phone number format for add contact: {}", newContact.getNumber());
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Phone number must contain at least 10 digits."));
        }
        newContact.setNumber(cleanedNumber); // Use the cleaned number

        if (newContact.getName() == null || newContact.getName().trim().isEmpty()) {
            logger.warn("Invalid name for add contact: Name is empty.");
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Contact name cannot be empty."));
        }
        newContact.setName(newContact.getName().trim());

        boolean success = contactService.addContactToCsv(newContact);

        if (success) {
            logger.info("Contact added successfully via service.");
            // Return success and the potentially modified contact (e.g., cleaned number)
            return ResponseEntity.ok(Map.of("success", true, "contact", newContact));
        } else {
            logger.error("ContactService failed to add contact to CSV.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "Failed to save contact to CSV. Check server logs."));
        }
    }

    /**
     * API Endpoint to get message history from the log file.
     */
    @GetMapping("/get-history")
    public ResponseEntity<List<HistoryEntry>> getHistory() {
        logger.info("Received GET request for /get-history");
        if (!Files.exists(HISTORY_LOG_FILE)) {
            logger.warn("History file not found: {}", HISTORY_LOG_FILE.toAbsolutePath());
            return ResponseEntity.ok(Collections.emptyList());
        }
        try {
            List<String> lines = Files.readAllLines(HISTORY_LOG_FILE, StandardCharsets.UTF_8);
            Collections.reverse(lines); // Show newest first
            List<HistoryEntry> history = lines.stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .map(line -> {
                        String[] parts = line.split(" \\| ", 4); // Split by " | ", limit 4 parts
                        if (parts.length == 4) {
                            // Create HistoryEntry DTO
                            return new HistoryEntry(parts[0], parts[1], parts[2], parts[3]);
                        } else {
                            logger.warn("Skipping malformed history line: {}", line);
                            return null; // Ignore lines that don't split correctly
                        }
                    })
                    .filter(java.util.Objects::nonNull) // Filter out nulls from malformed lines
                    .collect(Collectors.toList());
            return ResponseEntity.ok(history);
        } catch (IOException e) {
            logger.error("Error reading history log file '{}': {}", HISTORY_LOG_FILE.toAbsolutePath(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Collections.emptyList());
        }
    }

    /**
     * API Endpoint to start the message sending script asynchronously.
     * Accepts List<String> numbers directly from SendRequest DTO.
     */
    @PostMapping("/run-script")
    public ResponseEntity<Map<String, String>> runScript(@RequestBody SendRequest request) {
        logger.info("Received POST request for /run-script");
        String msgContent = request.getMessage();
        List<String> selectedNumbers = request.getNumbers(); // Get the list directly

        // Basic validation
        if (msgContent == null || msgContent.trim().isEmpty()) {
            logger.warn("Received invalid request for /run-script: Message empty.");
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Message field cannot be empty."));
        }
        if (selectedNumbers == null || selectedNumbers.isEmpty()) {
            logger.warn("Received invalid request for /run-script: No numbers selected/provided.");
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "No contact numbers were selected or entered."));
        }

        // Save message back to file
        try {
            logger.debug("Saving message content to {}", MSG_FILE);
            Files.writeString(MSG_FILE, msgContent, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            logger.warn("Could not save message text to file before starting script: {}", e.getMessage());
        }

        // Start the Selenium logic in a new thread
        logger.info("Triggering async Selenium send logic for {} numbers...", selectedNumbers.size());
        whatsappService.runSeleniumLogic(msgContent, selectedNumbers); // Pass the List<String>

        // Respond immediately
        return ResponseEntity.ok(Map.of("status", "success", "message", "Script started! Check application logs for progress."));
    }

    /**
     * API ENDPOINT FOR DELETION (If keeping)
     * Accepts List<String> numbers directly from SendRequest DTO.
     */
    @PostMapping("/delete-last-message")
    public ResponseEntity<Map<String, String>> deleteLastMessage(@RequestBody SendRequest request) { // Re-using SendRequest DTO
        logger.info("Received POST request for /delete-last-message");
        List<String> selectedNumbers = request.getNumbers(); // Get the list directly

        if (selectedNumbers == null || selectedNumbers.isEmpty()) {
            logger.warn("Received invalid request for /delete-last-message: No numbers selected.");
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "No valid phone numbers selected for deletion."));
        }

        try {
            // Start the Selenium delete logic in a new thread
            logger.info("Triggering async Selenium delete logic for {} numbers...", selectedNumbers.size());
            whatsappService.runSeleniumDeleteLogic(selectedNumbers); // Pass the List<String>

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
                logger.debug("Reading file: {}", path.toAbsolutePath());
                return Files.readString(path, StandardCharsets.UTF_8);
            } else {
                logger.warn("File not found: {}. Using default value.", path.toAbsolutePath());
            }
        } catch (IOException e) {
            logger.error("Error reading file {}: {}", path.toAbsolutePath(), e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error reading file {}: {}", path.toAbsolutePath(), e.getMessage());
        }
        return defaultValue;
    }
}