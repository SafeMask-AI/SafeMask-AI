package haitai.safemask;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SafemaskApplication {

	public static void main(String[] args) {
		SpringApplication.run(SafemaskApplication.class, args);
	}

}
