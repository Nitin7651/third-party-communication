package third.party.communication.whatsapp.dto;


public class HistoryEntry {
    private String timestamp;
    private String number;
    private String status;
    private String message;

    public HistoryEntry(String timestamp, String number, String status, String message) {
        this.timestamp = timestamp;
        this.number = number;
        this.status = status;
        this.message = message;
    }

    // Getters and Setters
    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    public String getNumber() { return number; }
    public void setNumber(String number) { this.number = number; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}