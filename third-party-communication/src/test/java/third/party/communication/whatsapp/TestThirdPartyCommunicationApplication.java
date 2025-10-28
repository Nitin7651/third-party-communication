package third.party.communication.whatsapp;

import org.springframework.boot.SpringApplication;

public class TestThirdPartyCommunicationApplication {

	public static void main(String[] args) {
		SpringApplication.from(ThirdPartyCommunicationApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
