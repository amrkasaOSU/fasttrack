package com.fasttrack.demo;

import com.fasttrack.account.User;
import com.fasttrack.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Fills the demo account on first boot so `docker compose up` gives a working
 * product with data, not an empty shell. Idempotent: if the demo account
 * already has applications, nothing happens.
 */
@Component
public class DemoSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeedRunner.class);

    private final AppProperties props;
    private final DemoAccountService demoAccounts;
    private final DemoDataSeeder seeder;
    private final DemoEmailSeeder emailSeeder;
    private final DemoResumeSeeder resumeSeeder;

    public DemoSeedRunner(AppProperties props,
                          DemoAccountService demoAccounts,
                          DemoDataSeeder seeder,
                          DemoEmailSeeder emailSeeder,
                          DemoResumeSeeder resumeSeeder) {
        this.props = props;
        this.demoAccounts = demoAccounts;
        this.seeder = seeder;
        this.emailSeeder = emailSeeder;
        this.resumeSeeder = resumeSeeder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.isDemoEnabled() || !props.isDemoSeedOnStartup()) {
            return;
        }
        try {
            User demo = demoAccounts.ensureDemoUser();
            int applications = seeder.seedApplications(demo);
            int emails = emailSeeder.seedEmails(demo.getId());
            boolean resume = resumeSeeder.seedResume(demo.getId());
            if (applications > 0 || emails > 0 || resume) {
                log.info("Demo account ready: {} applications, {} emails, resume seeded: {}",
                        applications, emails, resume);
            }
        } catch (RuntimeException ex) {
            // Seeding must never stop the application from starting.
            log.error("Demo seeding failed; the app will still start", ex);
        }
    }
}
