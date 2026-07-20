package com.glivt.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "tenants")
@Getter
@Setter
@NoArgsConstructor
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_code", nullable = false, unique = true, length = 64)
    private String companyCode;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(name = "app_name", nullable = false, length = 120)
    private String appName;

    @Column(name = "logo_url", length = 512)
    private String logoUrl;

    @Column(name = "splash_image_url", length = 512)
    private String splashImageUrl;

    @Column(name = "primary_color", nullable = false, length = 9)
    private String primaryColor = "#27D34D";

    @Column(name = "secondary_color", nullable = false, length = 9)
    private String secondaryColor = "#2A91BD";

    @Column(name = "support_phone", length = 32)
    private String supportPhone;

    @Column(name = "support_email", length = 160)
    private String supportEmail;

    @Column(name = "privacy_policy_url", length = 512)
    private String privacyPolicyUrl;

    @Column(name = "terms_url", length = 512)
    private String termsUrl;

    @Column(name = "enabled_modules", columnDefinition = "TEXT")
    private String enabledModules;

    @Column(name = "payment_enabled", nullable = false)
    private boolean paymentEnabled = false;

    @Column(name = "report_restrictions", columnDefinition = "TEXT")
    private String reportRestrictions;

    @Column(name = "max_history_days", nullable = false)
    private int maxHistoryDays = 90;

    @Column(name = "min_app_version", length = 32)
    private String minAppVersion;

    @Column(nullable = false, length = 16)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private TenantStatus status = TenantStatus.ACTIVE;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
