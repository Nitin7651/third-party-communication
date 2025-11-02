package third.party.communication.whatsapp.controller; // Adjust package

import com.opencsv.exceptions.CsvValidationException; // <-- Import
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile; // <-- Import
import third.party.communication.whatsapp.dto.Contact;
import third.party.communication.whatsapp.dto.DefaultResponse;
import third.party.communication.whatsapp.dto.HistoryEntry;
import third.party.communication.whatsapp.dto.SendRequest; // Expects List<String>
import third.party.communication.whatsapp.service.ContactService;
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
import java.util.stream.Stream;


@RestController
@CrossOrigin(origins = "http://localhost:3000")
public class ApiController {

    private static final Logger logger = LoggerFactory.getLogger(ApiController.class);
    private final WhatsappService whatsappService;
    private final ContactService contactService;

    // File paths managed by services now, only keep ones controller *directly* manages
    private final Path MSG_FILE = Paths.get(System.getProperty("user.dir"), "message.txt");
    private final Path HISTORY_LOG_FILE = Paths.get(System.getProperty("user.dir"), "history.log");

    @Autowired
    public ApiController(WhatsappService whatsappService, ContactService contactService) {
        this.whatsappService = whatsappService;
        this.contactService = contactService;
    }

    /**
     * API Endpoint to get default message from file.
     */
    @GetMapping("/get-defaults")
    public ResponseEntity<DefaultResponse> getDefaults() {
        logger.info("Received GET request for /get-defaults");
        String msg = readFileOrDefault(MSG_FILE, "Default message if file not found.");
        // We no longer send default numbers from here
        return ResponseEntity.ok(new DefaultResponse(msg, null));
    }

    /**
     * API Endpoint to get contacts from the database.
     */
    @GetMapping("/get-contacts")
    public ResponseEntity<List<Contact>> getContacts() {
        logger.info("Received GET request for /get-contacts");
        try {
            List<Contact> contacts = contactService.getAllContacts(); // <-- Use service
            return ResponseEntity.ok(contacts);
        } catch (Exception e) {
            logger.error("Error in /get-contacts controller: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Collections.emptyList());
        }
    }

    /**
     * API Endpoint to add a single manual contact.
     */
    @PostMapping("/add-contact")
    public ResponseEntity<Map<String, Object>> addContact(@RequestBody Contact newContact) {
        logger.info("Received POST request for /add-contact: {}", newContact.getName());
        try {
            // Service now handles validation and duplicate check
            Contact savedContact = contactService.addContact(newContact);
            // Return success + the newly saved contact
            return ResponseEntity.ok(Map.of("success", true, "contact", savedContact));
        } catch (IOException e) { // Catches duplicate or validation errors
            logger.warn("Failed to add contact: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error saving contact: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "Error saving contact. Check server logs."));
        }
    }

    /**
     * NEW: API Endpoint to upload a contacts CSV file.
     */
    @PostMapping("/upload-contacts")
    public ResponseEntity<Map<String, Object>> uploadContacts(@RequestParam("file") MultipartFile file) {
        logger.info("Received POST request for /upload-contacts: {}", file.getOriginalFilename());
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "File is empty."));
        }
        try {
            int importedCount = contactService.saveUploadedFile(file);
            logger.info("File processed, {} new contacts imported.", importedCount);
            // Return success + how many were added
            return ResponseEntity.ok(Map.of("success", true, "message", "File processed successfully. " + importedCount + " new contacts added."));
        } catch (CsvValidationException e) {
            logger.error("CSV validation error during upload: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "CSV parsing error: " + e.getMessage()));
        } catch (IOException e) {
            logger.error("IO error during upload: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "File error: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("Unexpected error during upload: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "An unexpected error occurred: " + e.getMessage()));
        }
    }


    /**
     * API Endpoint to get message history. (Unchanged)
     */
    @GetMapping("/get-history")
    public ResponseEntity<List<HistoryEntry>> getHistory() {
        // ... (This method remains the same) ...
        logger.info("Received GET request for /get-history");
        if (!Files.exists(HISTORY_LOG_FILE)) {
            logger.warn("History file not found: {}", HISTORY_LOG_FILE.toAbsolutePath());
            return ResponseEntity.ok(Collections.emptyList());
        }
        try (Stream<String> lines = Files.lines(HISTORY_LOG_FILE, StandardCharsets.UTF_8)) {
            List<HistoryEntry> history = lines
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
            Collections.reverse(history); // Ensure newest is first if log appends
            return ResponseEntity.ok(history);
        } catch (IOException e) {
            logger.error("Error reading history log file '{}': {}", HISTORY_LOG_FILE.toAbsolutePath(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Collections.emptyList());
        }
    }

    /**
     * API Endpoint to start sending messages. (Unchanged, already expects List<String>)
     */
    @PostMapping("/run-script")
    public ResponseEntity<Map<String, String>> runScript(@RequestBody SendRequest request) {
        logger.info("Received POST request for /run-script");
        String msgContent = request.getMessage();
        List<String> selectedNumbers = request.getNumbers(); // Get the list directly

        if (msgContent == null || msgContent.trim().isEmpty()) {
            logger.warn("Received invalid request for /run-script: Message empty.");
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Message field cannot be empty."));
        }
        if (selectedNumbers == null || selectedNumbers.isEmpty()) {
            logger.warn("Received invalid request for /run-script: No numbers selected/provided.");
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "No contact numbers were selected or entered."));
        }

        try {
            logger.debug("Saving message content to {}", MSG_FILE);
            Files.writeString(MSG_FILE, msgContent, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            logger.warn("Could not save message text to file before starting script: {}", e.getMessage());
        }

        logger.info("Triggering async Selenium send logic for {} numbers...", selectedNumbers.size());
        whatsappService.runSeleniumLogic(msgContent, selectedNumbers);

        return ResponseEntity.ok(Map.of("status", "success", "message", "Script started! Check application logs for progress."));
    }

    /**
     * API ENDPOINT FOR DELETION (Unchanged, already expects List<String>)
     */
    @PostMapping("/delete-last-message")
    public ResponseEntity<Map<String, String>> deleteLastMessage(@RequestBody SendRequest request) {
        logger.info("Received POST request for /delete-last-message");
        List<String> selectedNumbers = request.getNumbers();

        if (selectedNumbers == null || selectedNumbers.isEmpty()) {
            logger.warn("Received invalid request for /delete-last-message: No numbers selected.");
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "No valid phone numbers selected for deletion."));
        }

        try {
            logger.info("Triggering async Selenium delete logic for {} numbers...", selectedNumbers.size());
            whatsappService.runSeleniumDeleteLogic(selectedNumbers);

            return ResponseEntity.ok(Map.of("status", "success", "message", "Delete script started! Check application logs for progress."));
        } catch (Exception e) {
            logger.error("Error initiating /delete-last-message: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("status", "error", "message", "An internal server error occurred while starting delete script."));
        }
    }

    // --- NEW: API Endpoint to DELETE a contact ---
    /**
     * API Endpoint to delete a single contact from the database.
     */
    @DeleteMapping("/delete-contact/{number}")
    public ResponseEntity<Map<String, Object>> deleteContact(@PathVariable String number) {
        logger.info("Received DELETE request for /delete-contact/{}", number);

        try {
            boolean success = contactService.deleteContactByNumber(number);
            if (success) {
                logger.info("Successfully deleted contact {}", number);
                return ResponseEntity.ok(Map.of("success", true, "message", "Contact deleted successfully."));
            } else {
                logger.warn("Failed to delete contact {}, number not found.", number);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("success", false, "message", "Contact not found."));
            }
        } catch (Exception e) {
            logger.error("Error during contact deletion for number {}: {}", number, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "message", "Server error: " + e.getMessage()));
        }
    }
    // --- END NEW ---

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
        }
        return defaultValue;
    }
}