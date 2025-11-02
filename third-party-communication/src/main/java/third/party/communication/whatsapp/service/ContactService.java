package third.party.communication.whatsapp.service; // Adjust package

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import third.party.communication.whatsapp.dto.Contact;
import third.party.communication.whatsapp.repository.ContactRepository;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet; // Import HashSet
import java.util.List;
import java.util.Optional;
import java.util.Set; // Import Set
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Transactional
public class ContactService {

    private static final Logger logger = LoggerFactory.getLogger(ContactService.class);
    private static final Path CONTACTS_CSV_FILE = Paths.get(System.getProperty("user.dir"), "contacts.csv");

    // CSV Column Indices (Google Contacts format)
    private static final int GCSV_FIRST_NAME_INDEX = 0; // "First Name"
    private static final int GCSV_LAST_NAME_INDEX = 2;  // "Last Name"
    private static final int GCSV_PHONE1_VALUE_INDEX = 18; // "Phone 1 - Value"

    private final ContactRepository contactRepository;

    @Autowired
    public ContactService(ContactRepository contactRepository) {
        this.contactRepository = contactRepository;
    }

    /**
     * Fetches all contacts from the database.
     */
    @Transactional(readOnly = true)
    public List<Contact> getAllContacts() {
        logger.info("Fetching all contacts from database...");
        return contactRepository.findAll();
    }

    /**
     * Adds a single new contact to the database.
     */
    public Contact addContact(Contact newContact) throws IOException {
        logger.info("Attempting to add single contact: {}", newContact.getName());

        String cleanedNumber = newContact.getNumber().replaceAll("\\D", "");
        if (cleanedNumber.startsWith("91") && cleanedNumber.length() > 10) {
            cleanedNumber = cleanedNumber.substring(2); // Remove leading 91
        }
        if (!cleanedNumber.matches("\\d{10}")) {
            throw new IOException("Number must be exactly 10 digits.");
        }
        newContact.setNumber(cleanedNumber);

        if (contactRepository.existsByNumber(newContact.getNumber())) {
            logger.warn("Contact with number {} already exists.", newContact.getNumber());
            throw new IOException("Contact with this phone number already exists.");
        }

        Contact savedContact = contactRepository.save(newContact);
        logger.info("Successfully added new contact: {}", savedContact.getName());
        return savedContact;
    }

    /**
     * Parses an uploaded CSV file (with header) and saves new contacts to the database.
     */
    public int saveUploadedFile(MultipartFile file) throws IOException, CsvValidationException {
        if (file.isEmpty()) {
            throw new IOException("Failed to store empty file.");
        }

        String contentType = file.getContentType();
        if (contentType == null) {
            throw new IOException("File type is unknown.");
        }

        List<Contact> contactsToSave = new ArrayList<>();
        // --- NEW: Use a Set to track numbers we've already seen *in this file* ---
        Set<String> numbersInThisBatch = new HashSet<>();

        if (contentType.equals("text/csv") || file.getOriginalFilename().endsWith(".csv")) {
            logger.info("Parsing CSV file...");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
                 CSVReader csvReader = new CSVReader(reader)) {

                String[] header = csvReader.readNext();
                if (header == null) {
                    throw new IOException("CSV file is empty.");
                }

                String[] line;
                while ((line = csvReader.readNext()) != null) {
                    Optional<Contact> contactOpt = parseContactFromCsvLine(line);
                    if (contactOpt.isPresent()) {
                        Contact contact = contactOpt.get();
                        String number = contact.getNumber();

                        // --- MODIFIED DUPLICATE CHECK ---
                        // 1. Check if it's a duplicate within this *batch*
                        if (numbersInThisBatch.contains(number)) {
                            logger.warn("Skipping CSV row: Duplicate number found *within the file*. Name='{}', Number='{}'", contact.getName(), number);
                            continue; // Skip, it's a duplicate in the file
                        }
                        // 2. Check if it's already in the database
                        if (contactRepository.existsByNumber(number)) {
                            logger.warn("Skipping CSV row: Duplicate number *already in database*. Name='{}', Number='{}'", contact.getName(), number);
                            continue; // Skip, it's a duplicate in the DB
                        }
                        // --- END MODIFIED CHECK ---

                        // If checks pass, add to list and tracking set
                        contactsToSave.add(contact);
                        numbersInThisBatch.add(number);
                    }
                }
            }

        } else if (contentType.equals("application/vnd.ms-excel") || contentType.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                || file.getOriginalFilename().endsWith(".xls") || file.getOriginalFilename().endsWith(".xlsx")) {

            logger.warn("Excel parsing is not yet implemented. Please upload a CSV file.");
            throw new IOException("Excel file uploads are not supported yet. Please use CSV.");

        } else {
            throw new IOException("Unsupported file type: " + contentType + ". Please upload a CSV file.");
        }

        if (!contactsToSave.isEmpty()) {
            contactRepository.saveAll(contactsToSave);
            logger.info("Successfully saved {} new contacts to the database.", contactsToSave.size());
        } else {
            logger.info("No new contacts found to save from the uploaded file.");
        }

        return contactsToSave.size();
    }

    /**
     * Helper to parse a single line from the Google Contacts CSV.
     * Extracts combined Name and cleaned phone number.
     */
    private Optional<Contact> parseContactFromCsvLine(String[] parts) {
        if (parts.length > Math.max(GCSV_FIRST_NAME_INDEX, Math.max(GCSV_LAST_NAME_INDEX, GCSV_PHONE1_VALUE_INDEX))) {

            String firstName = parts[GCSV_FIRST_NAME_INDEX].trim();
            String lastName = parts[GCSV_LAST_NAME_INDEX].trim();
            String name = (firstName + " " + lastName).trim();

            String phone1 = parts[GCSV_PHONE1_VALUE_INDEX].trim();
            String cleanedNumber = phone1.replaceAll("\\D", "");

            if (cleanedNumber.startsWith("91") && cleanedNumber.length() > 10) {
                cleanedNumber = cleanedNumber.substring(2);
            }

            if (!name.isEmpty() && cleanedNumber.matches("\\d{10}")) {
                return Optional.of(new Contact(null, name, cleanedNumber));
            } else {
                logger.trace("Skipping CSV line - insufficient data or invalid 10-digit number: Name='{}', Phone='{}', Cleaned='{}'", name, phone1, cleanedNumber);
            }
        } else {
            logger.trace("Skipping CSV line - too few columns.");
        }
        return Optional.empty();
    }
    // --- NEW: Service method to delete a contact ---
    /**
     * Deletes a contact from the database based on their phone number.
     * @param number The 10-digit phone number.
     * @return true if deletion was successful, false if contact not found.
     */
    public boolean deleteContactByNumber(String number) {
        if (number == null || !number.matches("\\d{10}")) {
            logger.warn("Invalid number format for deletion: {}", number);
            return false;
        }

        if (contactRepository.existsByNumber(number)) {
            logger.info("Deleting contact with number: {}", number);
            contactRepository.deleteByNumber(number);
            // Verify deletion
            return !contactRepository.existsByNumber(number);
        } else {
            logger.warn("Delete failed: Contact with number {} not found.", number);
            return false; // Contact not found, so "deletion" wasn't needed/successful
        }
    }
    // --- END NEW ---
}