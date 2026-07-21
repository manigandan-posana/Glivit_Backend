package com.glivt.report;

import com.glivt.audit.AuditService;
import com.glivt.common.PageResponse;
import com.glivt.common.RequestContext;
import com.glivt.common.exception.BadRequestException;
import com.glivt.common.exception.ResourceNotFoundException;
import com.glivt.common.ratelimit.RateLimiter;
import com.glivt.device.Device;
import com.glivt.device.DeviceRepository;
import com.glivt.report.dto.ReportContent;
import com.glivt.report.dto.ReportDto;
import com.glivt.report.dto.ReportRequest;
import com.glivt.tenant.TenantRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ReportService {

    private final ReportRepository repository;
    private final DeviceRepository deviceRepository;
    private final TenantRepository tenantRepository;
    private final RateLimiter rateLimiter;
    private final AuditService auditService;
    private final JsonMapper jsonMapper = new JsonMapper();

    public ReportService(ReportRepository repository, DeviceRepository deviceRepository,
                         TenantRepository tenantRepository, RateLimiter rateLimiter,
                         AuditService auditService) {
        this.repository = repository;
        this.deviceRepository = deviceRepository;
        this.tenantRepository = tenantRepository;
        this.rateLimiter = rateLimiter;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public PageResponse<ReportDto> list(Long tenantId, Pageable pageable) {
        return PageResponse.from(repository.findByTenantIdOrderByCreatedAtDesc(tenantId, pageable),
                ReportDto::from);
    }

    @Transactional
    public ReportDto create(Long tenantId, Long userId, String username, ReportRequest request) {
        rateLimiter.check("report:" + tenantId + ":" + userId + ":" + RequestContext.getClientIp(),
                6, Duration.ofMinutes(10));
        validateWindow(tenantId, request);
        validateDevices(tenantId, request.deviceIds());

        ReportJob job = new ReportJob();
        job.setTenantId(tenantId);
        job.setRequestedBy(userId);
        job.setReportType(request.reportType().trim().toUpperCase());
        job.setFromTime(request.fromTime());
        job.setToTime(request.toTime());
        job.setOutputFormat(request.outputFormat() == null || request.outputFormat().isBlank()
                ? "CSV" : request.outputFormat().trim().toUpperCase());
        job.setFiltersJson(toJson(request));
        String fileName = "%s_%s.csv".formatted(job.getReportType().toLowerCase(), Instant.now().toEpochMilli());
        job.setFileName(fileName);
        job.setStatus(ReportStatus.COMPLETED);
        job.setCompletedAt(Instant.now());
        String content = buildCsv(tenantId, request);
        job.setFileSize((long) content.getBytes(StandardCharsets.UTF_8).length);
        job = repository.save(job);
        job.setDownloadUrl("/api/reports/" + job.getId() + "/content");
        job = repository.save(job);
        auditService.record(tenantId, userId, username, "CREATE_REPORT", "REPORT",
                String.valueOf(job.getId()), "SUCCESS", job.getReportType());
        return ReportDto.from(job);
    }

    @Transactional(readOnly = true)
    public ReportContent content(Long tenantId, Long id) {
        ReportJob job = repository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Report not found"));
        return new ReportContent(job.getFileName(), "text/csv", buildCsv(tenantId, null));
    }

    private void validateWindow(Long tenantId, ReportRequest request) {
        if (request.toTime().isBefore(request.fromTime())) {
            throw new BadRequestException("Report end time must be after start time");
        }
        int maxDays = tenantRepository.findById(tenantId).map(t -> t.getMaxHistoryDays()).orElse(90);
        Instant earliest = Instant.now().minus(maxDays, ChronoUnit.DAYS);
        if (request.fromTime().isBefore(earliest)) {
            throw new BadRequestException("Requested range exceeds tenant history limit");
        }
    }

    private void validateDevices(Long tenantId, List<Long> ids) {
        if (ids == null) {
            return;
        }
        for (Long id : ids) {
            if (deviceRepository.findByIdAndTenantId(id, tenantId).isEmpty()) {
                throw new BadRequestException("Requested device is not available for this tenant");
            }
        }
    }

    private String buildCsv(Long tenantId, ReportRequest request) {
        StringBuilder csv = new StringBuilder("device_id,name,imei,status,expiry_date\n");
        List<Device> devices = deviceRepository.search(tenantId, null, null, null, true,
                org.springframework.data.domain.PageRequest.of(0, 500)).getContent();
        for (Device device : devices) {
            if (request != null && request.deviceIds() != null
                    && !request.deviceIds().contains(device.getId())) {
                continue;
            }
            csv.append(device.getId()).append(',')
                    .append(escape(device.getName())).append(',')
                    .append(escape(device.getImei())).append(',')
                    .append(device.getStatus()).append(',')
                    .append(device.getExpiryDate() == null ? "" : device.getExpiryDate())
                    .append('\n');
        }
        return csv.toString();
    }

    private String toJson(Object value) {
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new BadRequestException("Report filters are invalid");
        }
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
