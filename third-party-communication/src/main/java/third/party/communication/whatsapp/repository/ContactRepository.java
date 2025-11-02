package third.party.communication.whatsapp.repository; // Adjust package

import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import third.party.communication.whatsapp.dto.Contact;

import java.util.Optional;

@Repository
public interface ContactRepository extends JpaRepository<Contact, Long> {

    // Check if a contact exists by their phone number
    boolean existsByNumber(String number);

    // Find a contact by their number (useful for checking duplicates)
    Optional<Contact> findByNumber(String number);
    // --- NEW: Method to delete by number ---
    // This needs to be transactional if called outside a @Transactional service method
    @Transactional
    void deleteByNumber(String number);
    // --- END NEW ---
}