package haitai.safemask.domain.monitoring.controller;

import haitai.safemask.domain.monitoring.dto.AdminMonitoringResponse;
import haitai.safemask.domain.monitoring.service.AdminMonitoringService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/monitoring")
public class AdminMonitoringController {

	private final AdminMonitoringService adminMonitoringService;

	public AdminMonitoringController(AdminMonitoringService adminMonitoringService) {
		this.adminMonitoringService = adminMonitoringService;
	}

	@GetMapping
	public AdminMonitoringResponse dashboard() {
		return adminMonitoringService.dashboard();
	}
}
