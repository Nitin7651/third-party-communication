package third.party.communication.whatsapp.dto; // Adjust package

import jakarta.persistence.*; // <-- Import Jakarta Persistence
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity // <-- Mark as an Entity
@Table(name = "contacts") // <-- Define table name
public class Contact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id; // Primary Key

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true) // Numbers should be unique
    private String number; // Store the cleaned 10-digit number
}