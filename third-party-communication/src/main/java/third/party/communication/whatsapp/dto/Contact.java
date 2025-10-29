package third.party.communication.whatsapp.dto; // Adjust package name if needed

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor // Default constructor needed for JSON parsing if used in requests
@AllArgsConstructor // Constructor for creating objects easily
public class Contact {
    private String name;
    private String number;

    // Manual Getters/Setters/Constructors if not using Lombok
}