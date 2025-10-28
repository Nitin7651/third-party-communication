package third.party.communication.whatsapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class ThirdPartyCommunicationApplication {

	public static void main(String[] args) {
		SpringApplication.run(ThirdPartyCommunicationApplication.class, args);
	}

}
