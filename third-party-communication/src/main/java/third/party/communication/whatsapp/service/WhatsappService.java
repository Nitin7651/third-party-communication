package third.party.communication.whatsapp.service; // Ensure this matches your package structure

import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.TimeoutException; // Correct import
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;


@Service
public class WhatsappService {

    // --- Configuration ---
    private static final int NEW_MSG_TIME_SEC = 5;
    private static final int SEND_MSG_TIME_SEC = 5; // Wait after send
    private static final int ACTION_TIME_MS = 2000;
    private static final String COUNTRY_CODE = "91";
    private static final String IMAGE_PATH_STR = "image.png";

    // --- Paths ---
    private static final Path BASE_DIR = Paths.get(System.getProperty("user.dir"));
    private static final Path MSG_FILE = BASE_DIR.resolve("message.txt");
    private static final Path NUMS_FILE = BASE_DIR.resolve("numbers.txt");
    private static final Path IMAGE_FILE = BASE_DIR.resolve(IMAGE_PATH_STR);
    private static final Path SESSION_DIR = BASE_DIR.resolve("whatsapp_session");
    private static final Path HISTORY_LOG_FILE = BASE_DIR.resolve("history.log");

    private static final Logger logger = LoggerFactory.getLogger(WhatsappService.class);

    /**
     * Appends a new entry to the history.log file. Made synchronized.
     */
    private synchronized void logStatus(String number, String status, String message) {
        try {
            String msgSummary = message.replace('\n', ' ').trim();
            if (msgSummary.isEmpty()) {
                msgSummary = "N/A";
            } else if (msgSummary.length() > 40) {
                msgSummary = msgSummary.substring(0, 37) + "...";
            }

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String logEntry = String.format("%s | %s | %s | %s%n", timestamp, number, status, msgSummary);

            Files.createDirectories(HISTORY_LOG_FILE.getParent());

            Files.writeString(HISTORY_LOG_FILE, logEntry, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            logger.debug("Logged status: {}", logEntry.trim());
        } catch (IOException e) {
            logger.error("Failed to write to log file '{}': {}", HISTORY_LOG_FILE.toAbsolutePath(), e.getMessage(), e);
        } catch (Exception e) {
            logger.error("Unexpected error writing to log: {}", e.getMessage(), e);
        }
    }

    /**
     * Main Selenium logic for sending messages. Runs asynchronously.
     */
    @Async
    public void runSeleniumLogic(String msg, List<String> numbers) {
        logger.info("--- Starting WhatsApp send script ---");
        if (numbers == null || numbers.isEmpty()) {
            logger.warn("No valid numbers provided for sending.");
            return;
        }
        logger.info("Received {} numbers to process.", numbers.size());

        WebDriver driver = null;
        long startTime = System.currentTimeMillis();
        try {
            logger.info("Setting up ChromeDriver for SEND operation...");
            WebDriverManager.chromedriver().setup();
            ChromeOptions options = new ChromeOptions();
            String sessionPath = SESSION_DIR.toAbsolutePath().toString();
            options.addArguments("user-data-dir=" + sessionPath);
            // options.addArguments("--headless");
            // options.addArguments("--no-sandbox");
            // options.addArguments("--disable-dev-shm-usage");
            // options.addArguments("--window-size=1920,1080");

            logger.info("Initializing ChromeDriver with session path: {}", sessionPath);
            driver = new ChromeDriver(options);
            driver.manage().window().maximize();

            logger.info("Navigating to WhatsApp Web...");
            driver.get("https://web.whatsapp.com");

            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(60));
            logger.info("Waiting for WhatsApp Web to load (max 60 seconds)... Scan QR code if needed.");

            wait.until(ExpectedConditions.presenceOfElementLocated(By.xpath("//div[@id='pane-side']")));
            logger.info("WhatsApp Web loaded successfully. Logged in!");

            boolean imageToSend = Files.exists(IMAGE_FILE);
            logger.info("Image file {} {}found.", IMAGE_FILE.getFileName(), imageToSend ? "" : "NOT ");

            for (String num : numbers) {
                String numDigits = num.replaceAll("\\D", "");
                if (numDigits.isEmpty()) {
                    logger.warn("Skipping invalid number format: {}", num);
                    continue;
                }
                String link = String.format("https://web.whatsapp.com/send/?phone=%s%s&text=", COUNTRY_CODE, numDigits);
                logger.info("Navigating to chat link for number: {}", numDigits);
                driver.get(link);

                WebElement chatBoxElement = null;
                boolean isValidNumber = true;
                try {
                    logger.debug("Waiting for chat input or invalid number popup...");
                    WebDriverWait chatWait = new WebDriverWait(driver, Duration.ofSeconds(15));
                    boolean conditionMet = chatWait.until(ExpectedConditions.or(
                            ExpectedConditions.presenceOfElementLocated(By.xpath("//div[@data-lexical-editor='true'][@role='textbox']")),
                            ExpectedConditions.presenceOfElementLocated(By.xpath("//div[@data-testid='popup-controls-ok']"))
                    ));
                    if (conditionMet) {
                        try {
                            WebElement invalidNumPopup = driver.findElement(By.xpath("//div[@data-testid='popup-controls-ok']"));
                            logger.warn("❌ {} is not a valid WhatsApp number (popup detected). Skipping.", num);
                            logStatus(numDigits, "Invalid Number", "N/A");
                            invalidNumPopup.click();
                            isValidNumber = false;
                            Thread.sleep(1000);
                        } catch (org.openqa.selenium.NoSuchElementException e) {
                            try {
                                chatBoxElement = driver.findElement(By.xpath("//div[@data-lexical-editor='true'][@role='textbox']"));
                                logger.info("Chat box found for {} ({}). Proceeding...", num, numDigits);
                            } catch (org.openqa.selenium.NoSuchElementException e2) {
                                logger.error("Neither chat box nor OK button found for {} ({}) after wait succeeded. Skipping.", num, numDigits);
                                logStatus(numDigits, "Chat Not Ready", "Error - Element Confusion");
                                isValidNumber = false;
                            }
                        }
                    } else {
                        logger.error("ExpectedConditions.or() returned false unexpectedly for {} ({}). Skipping.", num, numDigits);
                        logStatus(numDigits, "Chat Not Ready", "Error - Wait Condition Failed");
                        isValidNumber = false;
                    }
                } catch (org.openqa.selenium.TimeoutException e) {
                    logger.warn("Chat not ready for {} ({}) within 15 seconds. Skipping.", num, numDigits);
                    logStatus(numDigits, "Chat Not Ready", "Timeout");
                    isValidNumber = false;
                } catch (Exception e) {
                    logger.error("Unexpected error checking chat readiness for {} ({}): {}", num, numDigits, e.getMessage(), e);
                    logStatus(numDigits, "Chat Not Ready", "Error");
                    isValidNumber = false;
                }

                if (!isValidNumber) {
                    continue;
                }

                boolean imageAttached = false;
                WebElement imageSendButton = null; // Store image send button if found
                if (imageToSend) {
                    logger.debug("Attempting to attach image...");
                    try {
                        WebElement attachBtn = new WebDriverWait(driver, Duration.ofSeconds(10)).until(
                                ExpectedConditions.elementToBeClickable(By.cssSelector("span[data-icon='clip']"))
                        );
                        attachBtn.click();
                        WebElement fileInput = new WebDriverWait(driver, Duration.ofSeconds(10)).until(
                                ExpectedConditions.presenceOfElementLocated(By.xpath("//input[@accept='image/*,video/mp4,video/3gpp,video/quicktime']"))
                        );
                        String imageAbsolutePath = IMAGE_FILE.toAbsolutePath().toString();
                        fileInput.sendKeys(imageAbsolutePath);
                        // Wait for the send button in the image preview
                        imageSendButton = new WebDriverWait(driver, Duration.ofSeconds(20)).until(
                                ExpectedConditions.elementToBeClickable(By.xpath("//button[@aria-label='Send']"))
                        );
                        imageAttached = true;
                        logger.info("Image attached successfully for {}.", numDigits);
                        // Find the caption input box
                        chatBoxElement = new WebDriverWait(driver, Duration.ofSeconds(10)).until(
                                ExpectedConditions.presenceOfElementLocated(By.xpath("//div[@data-lexical-editor='true'][@role='textbox']"))
                        );
                        logger.debug("Found caption input box after attaching image.");

                    } catch (Exception e) {
                        logger.error("Image upload failed for {}: {}", numDigits, e.getMessage(), e);
                        logStatus(numDigits, "Image Upload Fail", "N/A");
                        try { driver.findElement(By.xpath("//button[@aria-label='Close']")).click(); } catch (Exception closeEx) { /* Ignore */ }
                        imageAttached = false;
                    }
                }

                logger.debug("Attempting to send message/caption...");
                try {
                    // Ensure chatBoxElement is assigned (re-find if necessary)
                    if (chatBoxElement == null) {
                        logger.debug("Re-finding chat box element before typing...");
                        chatBoxElement = new WebDriverWait(driver, Duration.ofSeconds(10)).until(
                                ExpectedConditions.presenceOfElementLocated(By.xpath("//div[@data-lexical-editor='true'][@role='textbox']"))
                        );
                    }

                    Actions actions = new Actions(driver);
                    String[] lines = msg.split("\\r?\\n");
                    for (int i = 0; i < lines.length; i++) {
                        actions.moveToElement(chatBoxElement).sendKeys(lines[i]);
                        if (i < lines.length - 1) {
                            actions.keyDown(Keys.SHIFT).sendKeys(Keys.ENTER).keyUp(Keys.SHIFT);
                        }
                    }

                    // --- FIX: Revert to using Keys.ENTER for text, click for image ---
                    if (imageAttached && imageSendButton != null) {
                        actions.perform(); // Type caption first
                        logger.debug("Clicking image send button...");
                        // Use Javascript click as a robust fallback for image send button
                        try {
                            imageSendButton.click();
                        } catch (Exception clickEx) {
                            logger.warn("Standard click failed for image send button, trying JS click...", clickEx);
                            JavascriptExecutor js = (JavascriptExecutor) driver;
                            js.executeScript("arguments[0].click();", imageSendButton);
                        }
                    } else if (!imageAttached) {
                        logger.debug("Sending text message with Keys.ENTER...");
                        actions.sendKeys(Keys.ENTER).perform(); // Send text message
                    } else {
                        // Image attach failed or button not found, log error
                        throw new Exception("Cannot send image, send button not found after attach attempt.");
                    }
                    // --- END FIX ---


                    logger.info("✅ Message sent to {}", numDigits);
                    logStatus(numDigits, "Success", msg);
                    Thread.sleep(SEND_MSG_TIME_SEC * 1000);

                } catch (Exception e) {
                    logger.error("❌ Failed to send message to {}: {}", numDigits, e.getMessage(), e);
                    logStatus(numDigits, "Send Fail", "Send Action Error");
                }
            } // end for loop

            logger.info("--- Message sending loop finished. ---");

        } catch (Exception e) {
            logger.error("--- AN UNEXPECTED ERROR OCCURRED in Selenium Send Logic ---", e);
        } finally {
            if (driver != null) {
                try {
                    driver.quit();
                    logger.info("ChromeDriver quit successfully after SEND operation.");
                } catch (Exception e) {
                    logger.error("Error quitting ChromeDriver after SEND: {}", e.getMessage());
                }
            }
            long endTime = System.currentTimeMillis();
            logger.info("--- Send script execution finished in {} ms ---", (endTime - startTime));
        }
    }

    // --- Selenium Logic (DELETE MESSAGE - unchanged) ---
    @Async
    public void runSeleniumDeleteLogic(List<String> numbers) {
        logger.info("--- Starting WhatsApp DELETE script ---");
        if (numbers == null || numbers.isEmpty()) {
            logger.warn("No valid numbers provided for deletion.");
            return;
        }
        logger.info("Attempting to delete last message for {} numbers.", numbers.size());

        WebDriver driver = null;
        long startTime = System.currentTimeMillis();
        try {
            logger.info("Setting up ChromeDriver for DELETE operation...");
            WebDriverManager.chromedriver().setup();
            ChromeOptions options = new ChromeOptions();
            String sessionPath = SESSION_DIR.toAbsolutePath().toString();
            options.addArguments("user-data-dir=" + sessionPath);
            // options.addArguments("--headless");

            logger.info("Initializing ChromeDriver with session path: {}", sessionPath);
            driver = new ChromeDriver(options);
            driver.manage().window().maximize();

            logger.info("Navigating to WhatsApp Web for DELETE...");
            driver.get("https://web.whatsapp.com");

            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(60));
            logger.info("Waiting for WhatsApp Web to load (max 60 seconds)...");
            wait.until(ExpectedConditions.presenceOfElementLocated(By.xpath("//div[@id='pane-side']")));
            logger.info("WhatsApp Web loaded successfully. Logged in!");

            for (String num : numbers) {
                String numDigits = num.replaceAll("\\D", "");
                if (numDigits.isEmpty()) {
                    logger.warn("Skipping invalid number format for delete: {}", num);
                    continue;
                }
                String link = String.format("https://web.whatsapp.com/send/?phone=%s%s&text=", COUNTRY_CODE, numDigits);
                logger.info("Navigating to chat link for DELETE operation: {}", numDigits);
                driver.get(link);

                try {
                    logger.debug("Waiting for chat text box for number {}...", num);
                    WebDriverWait chatBoxWait = new WebDriverWait(driver, Duration.ofSeconds(15));
                    chatBoxWait.until(ExpectedConditions.presenceOfElementLocated(By.xpath("//div[@data-lexical-editor='true'][@role='textbox']")));
                    logger.debug("Chat text box found.");

                } catch (Exception e) {
                    logger.warn("Chat not ready for {}. Cannot proceed with delete. Skipping. Error: {}", num, e.getMessage());
                    logStatus(numDigits, "Delete Fail", "Chat Not Found");
                    continue;
                }

                try {
                    logger.debug("Attempting delete steps for number {}...", num);
                    logger.debug("Waiting up to 15 seconds for at least one outgoing message to load...");
                    WebDriverWait messageWait = new WebDriverWait(driver, Duration.ofSeconds(15));
                    messageWait.until(
                            ExpectedConditions.presenceOfElementLocated(By.xpath("//div[contains(@class, 'message-out')]"))
                    );
                    logger.debug("Outgoing message found. Proceeding...");
                    Thread.sleep(1000);

                    List<WebElement> allMyMessages = driver.findElements(By.xpath("//div[contains(@class, 'message-out')]"));
                    logger.debug("Found {} outgoing messages.", allMyMessages.size());

                    if (allMyMessages.isEmpty()) {
                        logger.warn("No messages sent by you found in chat with {}. Skipping delete.", num);
                        logStatus(numDigits, "Delete Fail", "No Sent Messages");
                        continue;
                    }

                    WebElement lastMessage = allMyMessages.get(allMyMessages.size() - 1);

                    logger.debug("Hovering over the last message to reveal menu...");
                    Actions actions = new Actions(driver);
                    actions.moveToElement(lastMessage).perform();
                    Thread.sleep(1500);

                    logger.debug("Waiting for and clicking message menu arrow...");
                    WebElement arrow = new WebDriverWait(driver, Duration.ofSeconds(7)).until(
                            ExpectedConditions.elementToBeClickable(
                                    lastMessage.findElement(By.xpath(".//div[@role='button'][.//span[@data-icon='menu-down']]"))
                            )
                    );
                    arrow.click();
                    logger.debug("Clicked message menu arrow.");

                    logger.debug("Starting 3-click delete process...");

                    logger.debug("Waiting for 'Delete' menu item...");
                    WebElement deleteBtn = new WebDriverWait(driver, Duration.ofSeconds(7)).until(
                            ExpectedConditions.elementToBeClickable(By.xpath("//div[@data-testid='message-menu-delete']"))
                    );
                    logger.debug("Clicking 'Delete' menu item...");
                    deleteBtn.click();

                    logger.debug("Waiting for 'Delete for everyone' button...");
                    WebElement deleteForEveryoneBtn = new WebDriverWait(driver, Duration.ofSeconds(7)).until(
                            ExpectedConditions.elementToBeClickable(By.xpath("//button[@data-testid='popup-controls-delete-for-everyone']"))
                    );
                    logger.debug("Clicking 'Delete for everyone' button...");
                    deleteForEveryoneBtn.click();

                    logger.debug("Waiting for final 'OK' confirmation button...");
                    WebElement okBtn = new WebDriverWait(driver, Duration.ofSeconds(7)).until(
                            ExpectedConditions.elementToBeClickable(By.xpath("//button[@data-testid='popup-controls-ok']"))
                    );
                    logger.debug("Clicking final 'OK' button...");
                    okBtn.click();

                    logger.info("✅ Last message to {} deleted successfully.", num);
                    logStatus(numDigits, "Delete Success", "Last message deleted");
                    Thread.sleep(SEND_MSG_TIME_SEC * 1000);

                } catch (Exception e) {
                    logger.error("❌ Failed to delete message for {}: {}", num, e.getMessage(), e);
                    logStatus(numDigits, "Delete Fail", "Button/Option not found");
                    try {
                        logger.debug("Attempting to click Cancel button after error...");
                        WebElement cancelButton = new WebDriverWait(driver, Duration.ofSeconds(3)).until(
                                ExpectedConditions.elementToBeClickable((By.xpath("//button[@data-testid='popup-controls-cancel']")))
                        );
                        cancelButton.click();
                        logger.debug("Clicked Cancel button.");
                    } catch (Exception cancelEx) {
                        logger.debug("Cancel button not found or not clickable: {}", cancelEx.getMessage());
                    }
                }
            } // End of for loop

            logger.info("--- All delete operations attempted. Check logs for individual results. ---");

        } catch (Exception e) {
            logger.error("--- AN UNEXPECTED ERROR OCCURRED during DELETE script ---", e);
        } finally {
            if (driver != null) {
                try {
                    driver.quit();
                    logger.info("ChromeDriver quit successfully after DELETE operation.");
                } catch (Exception e) {
                    logger.error("Error quitting ChromeDriver after DELETE: {}", e.getMessage());
                }
            }
            long endTime = System.currentTimeMillis();
            logger.info("--- Delete script execution finished in {} ms ---", (endTime - startTime));
        }
    } // End of runSeleniumDeleteLogic
}

