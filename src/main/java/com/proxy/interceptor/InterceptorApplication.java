package com.proxy.interceptor;

import com.proxy.interceptor.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@RequiredArgsConstructor
@Slf4j
public class InterceptorApplication {

	public static void main(String[] args) {
		SpringApplication.run(InterceptorApplication.class, args);
	}

	CommandLineRunner initAdmin(AuthService authService) {
		return args -> {
			// Create default admin user if not exists
			authService.createAdminIfNotExists("admin", "14495abc");
			log.info("Interceptor Proxy started");
			log.info("Dashboard: http://localhost:3000");
		};
	}

}
