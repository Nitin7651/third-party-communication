package third.party.communication.whatsapp.dto;


public class DefaultResponse {
    private String defaultMessage;
    private String defaultNumbers;

    public DefaultResponse(String defaultMessage, String defaultNumbers) {
        this.defaultMessage = defaultMessage;
        this.defaultNumbers = defaultNumbers;
    }

    // Getters and Setters
    public String getDefaultMessage() { return defaultMessage; }
    public void setDefaultMessage(String defaultMessage) { this.defaultMessage = defaultMessage; }
    public String getDefaultNumbers() { return defaultNumbers; }
    public void setDefaultNumbers(String defaultNumbers) { this.defaultNumbers = defaultNumbers; }
}