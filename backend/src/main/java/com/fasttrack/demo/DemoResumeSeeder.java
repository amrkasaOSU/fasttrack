package com.fasttrack.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasttrack.resume.Resume;
import com.fasttrack.resume.ResumeParseMethod;
import com.fasttrack.resume.ResumeProfile;
import com.fasttrack.resume.ResumeRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gives the demo account a resume, so the Fit Checker demonstrates something
 * instead of asking a visitor to upload one.
 *
 * <p>The profile is written directly rather than parsed from a PDF: the
 * parsing path is covered by tests, and generating a PDF at start-up to
 * immediately re-read it would be theatre. The text is stored too, because
 * the ATS keyword comparison needs it.
 */
@Service
public class DemoResumeSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoResumeSeeder.class);

    private static final String RESUME_TEXT = """
            Demo User
            demo@fasttrack.app | Columbus, OH | github.com/demo

            EDUCATION
            The Ohio State University
            Bachelor of Science in Computer Science and Engineering
            Expected graduation May 2027

            SKILLS
            Languages: Java, Python, JavaScript, TypeScript, SQL, Bash
            Frameworks: Spring Boot, React, Next.js, Hibernate, Tailwind CSS
            Databases: PostgreSQL, Redis, MongoDB
            Tools: Git, Docker, JUnit, Playwright, Jira, Agile
            Cloud: AWS

            EXPERIENCE
            Software Engineering Intern - Regional Insurance Group, Summer 2026
            - Built six REST endpoints in Spring Boot backed by PostgreSQL for the claims portal
            - Added JUnit and Testcontainers coverage to a service that previously had none
            - Containerised a nightly batch job with Docker and moved it onto the CI pipeline

            Undergraduate Teaching Assistant - OSU CSE 2221, 2025-2026
            - Ran weekly lab sections for 30 students on data structures and software design
            - Graded and gave written feedback on 200+ programming assignments

            PROJECTS
            FastTrack - internship application tracker
            - Spring Boot API with PostgreSQL and Redis, Next.js frontend, Docker Compose
            - Classifies application email and scores resume fit against job postings

            Transit delay dashboard
            - Python ETL loading five years of transit data, React dashboard over a REST API

            ACTIVITIES
            Hack OHI/O participant; ACM student chapter member
            US Citizen
            """;

    private final ResumeRepository resumes;
    private final ObjectMapper mapper;

    public DemoResumeSeeder(ResumeRepository resumes, ObjectMapper llmObjectMapper) {
        this.resumes = resumes;
        this.mapper = llmObjectMapper;
    }

    @Transactional
    public boolean seedResume(UUID demoUserId) {
        if (resumes.countByUserId(demoUserId) > 0) {
            return false;
        }

        ResumeProfile profile = new ResumeProfile(
                List.of("Java", "Python", "JavaScript", "TypeScript", "SQL", "Bash"),
                List.of("Spring Boot", "React", "Next.js", "Hibernate", "Tailwind CSS"),
                List.of("PostgreSQL", "Redis", "MongoDB"),
                List.of("Git", "Docker", "JUnit", "Playwright", "Jira", "Agile"),
                List.of("AWS"),
                List.of(
                        new ResumeProfile.ExperienceEntry(
                                "Software Engineering Intern", "Regional Insurance Group", "Summer 2026",
                                List.of(
                                        "Built six REST endpoints in Spring Boot backed by PostgreSQL for the claims portal",
                                        "Added JUnit and Testcontainers coverage to a service that previously had none",
                                        "Containerised a nightly batch job with Docker and moved it onto the CI pipeline")),
                        new ResumeProfile.ExperienceEntry(
                                "Undergraduate Teaching Assistant", "OSU CSE 2221", "2025-2026",
                                List.of(
                                        "Ran weekly lab sections for 30 students on data structures and software design",
                                        "Graded and gave written feedback on 200+ programming assignments"))),
                List.of(
                        new ResumeProfile.ProjectEntry("FastTrack",
                                "Internship application tracker: Spring Boot API with PostgreSQL and Redis, "
                                + "Next.js frontend, Docker Compose. Classifies application email and scores "
                                + "resume fit against job postings.",
                                List.of("Java", "Spring Boot", "PostgreSQL", "Redis", "Next.js", "Docker")),
                        new ResumeProfile.ProjectEntry("Transit delay dashboard",
                                "Python ETL loading five years of transit data, React dashboard over a REST API.",
                                List.of("Python", "React", "PostgreSQL"))),
                new ResumeProfile.Education("The Ohio State University", "Bachelor of Science",
                        "Computer Science and Engineering", "May 2027"),
                "US Citizen");

        Resume resume = new Resume(demoUserId, 1, "demo-resume.pdf",
                RESUME_TEXT.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, RESUME_TEXT);
        try {
            resume.storeParsed(mapper.writeValueAsString(profile), ResumeParseMethod.HEURISTIC);
        } catch (com.fasterxml.jackson.core.JacksonException ex) {
            log.warn("Could not seed the demo resume profile", ex);
            return false;
        }
        resume.setActive(true);
        resumes.save(resume);

        log.info("Seeded the demo account's resume");
        return true;
    }
}
