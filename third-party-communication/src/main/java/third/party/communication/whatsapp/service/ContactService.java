package third.party.communication.whatsapp.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import third.party.communication.whatsapp.dto.Contact;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class ContactService {

    private static final Logger logger = LoggerFactory.getLogger(ContactService.class);
    private static final Path CONTACTS_CSV_FILE = Paths.get(System.getProperty("user.dir"), "contacts.csv");
    // Define expected header indices based on Google Contacts CSV structure
    private static final int GCSV_FIRST_NAME_INDEX = 0;
    // Potentially combine other name parts if needed, e.g., Last Name (index 2)
    private static final int GCSV_PHONE1_VALUE_INDEX = 18; // "Phone 1 - Value"

    /**
     * Reads contacts from the CSV file.
     * Assumes Google Contacts CSV format.
     * @return List of Contact objects.
     */
    public List<Contact> loadContactsFromCsv() {
        logger.info("Attempting to load contacts from {}", CONTACTS_CSV_FILE.toAbsolutePath());
        List<Contact> contacts = new ArrayList<>();
        if (!Files.exists(CONTACTS_CSV_FILE)) {
            logger.warn("Contacts file not found: {}", CONTACTS_CSV_FILE.toAbsolutePath());
            // Create a default file? Or just return empty? Returning empty for now.
            // createFileWithHeader(CONTACTS_CSV_FILE); // Optional: create if not exists
            return contacts;
        }

        try (Stream<String> lines = Files.lines(CONTACTS_CSV_FILE, StandardCharsets.UTF_8)) {
            contacts = lines
                    .skip(1) // Skip header row
                    .map(line -> line.split(",", -1)) // Split by comma, keep trailing empty strings
                    .map(this::parseContactFromCsvLine) // Use helper method
                    .filter(Optional::isPresent)    // Filter out lines that couldn't be parsed
                    .map(Optional::get)             // Get the Contact object
                    .collect(Collectors.toList());
            logger.info("Successfully loaded {} contacts from CSV.", contacts.size());
        } catch (IOException e) {
            logger.error("Error reading contacts file {}: {}", CONTACTS_CSV_FILE.toAbsolutePath(), e.getMessage(), e);
        } catch (Exception e) {
            logger.error("Unexpected error processing contacts file {}: {}", CONTACTS_CSV_FILE.toAbsolutePath(), e.getMessage(), e);
        }
        return contacts;
    }

    /**
     * Helper to parse a single line from the Google Contacts CSV.
     * Extracts Name and the first valid phone number.
     * Cleans the phone number to digits only.
     */
    private Optional<Contact> parseContactFromCsvLine(String[] parts) {
        if (parts.length > Math.max(GCSV_FIRST_NAME_INDEX, GCSV_PHONE1_VALUE_INDEX)) {
            String name = parts[GCSV_FIRST_NAME_INDEX].trim();
            String phone1 = parts[GCSV_PHONE1_VALUE_INDEX].trim();
            // Add logic here if you need to check Phone 2 etc.

            String cleanedNumber = phone1.replaceAll("\\D", ""); // Remove non-digits

            // Basic validation: Name exists and number looks like a phone number (e.g., >= 10 digits)
            // Adjust minimum length as needed for your region
            if (!name.isEmpty() && cleanedNumber.length() >= 10) {
                // You might want more sophisticated phone number validation
                return Optional.of(new Contact(name, cleanedNumber));
            } else {
                logger.trace("Skipping CSV line - insufficient data or invalid number: Name='{}', Phone='{}', Cleaned='{}'", name, phone1, cleanedNumber);
            }
        } else {
            logger.trace("Skipping CSV line - too few columns: {}", (Object) parts); // Log the parts array
        }
        return Optional.empty();
    }

    /**
     * Appends a new contact to the CSV file.
     * @param newContact The Contact object to add.
     * @return true if successful, false otherwise.
     */
    public boolean addContactToCsv(Contact newContact) {
        if (newContact == null || newContact.getName() == null || newContact.getName().trim().isEmpty()
                || newContact.getNumber() == null || !newContact.getNumber().matches("\\d{10,}")) { // Basic validation
            logger.warn("Attempted to add invalid contact: {}", newContact);
            return false;
        }

        logger.info("Attempting to add contact: Name={}, Number={}", newContact.getName(), newContact.getNumber());

        // Create a basic CSV line (adjust if your CSV needs more columns)
        // Ensure proper CSV quoting if names contain commas
        String lineToAdd = String.format("\"%s\",,,,,,,,,,,,,,,,,,\"%s\"\n",
                newContact.getName().replace("\"", "\"\""), // Basic CSV quote escaping
                newContact.getNumber()); // Assuming number has no special chars

        boolean fileExisted = Files.exists(CONTACTS_CSV_FILE);

        try (BufferedWriter writer = Files.newBufferedWriter(CONTACTS_CSV_FILE, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            if (!fileExisted) {
                logger.info("Contacts file not found, creating new file with header.");
                // Write Google Contacts compatible header if creating the file
                writer.write("First Name,Middle Name,Last Name,Phonetic First Name,Phonetic Middle Name,Phonetic Last Name,Name Prefix,Name Suffix,Nickname,File As,Organization Name,Organization Title,Organization Department,Birthday,Notes,Photo,Labels,Phone 1 - Label,Phone 1 - Value,Phone 2 - Label,Phone 2 - Value\n"); // Simplified header
            }
            writer.write(lineToAdd);
            logger.info("Successfully added contact to {}", CONTACTS_CSV_FILE.getFileName());
            return true;
        } catch (IOException e) {
            logger.error("Error writing contact to file {}: {}", CONTACTS_CSV_FILE.toAbsolutePath(), e.getMessage(), e);
            return false;
        }
    }

    // Optional helper to create file if needed
    // private void createFileWithHeader(Path path) { ... }
}