package third.party.communication.whatsapp.dto; // Adjust package name if needed

import lombok.Getter;
import lombok.Setter;
import java.util.List; // <-- Import List


@Getter
@Setter
public class SendRequest {
    private String message;
    private String numbers; // <<< MUST BE List<String>
}