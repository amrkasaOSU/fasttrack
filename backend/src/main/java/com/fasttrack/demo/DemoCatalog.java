package com.fasttrack.demo;

import java.util.List;

/**
 * The vocabulary the demo generator draws from.
 *
 * <p>Companies and roles are real-sounding but the data is entirely synthetic -
 * no real email, no real person, nothing scraped. It exists so a recruiter can
 * open the demo and see a populated product.
 */
final class DemoCatalog {

    private DemoCatalog() {
    }

    record Employer(String name, String atsPlatform, String emailDomain, String type) {}

    /** type is used for the "response rate by company type" insight. */
    static final List<Employer> EMPLOYERS = List.of(
            new Employer("Stripe", "Greenhouse", "greenhouse-mail.io", "Tech - Large"),
            new Employer("Datadog", "Greenhouse", "greenhouse-mail.io", "Tech - Large"),
            new Employer("Snowflake", "Workday", "myworkday.com", "Tech - Large"),
            new Employer("Palantir", "Lever", "lever.co", "Tech - Large"),
            new Employer("Cloudflare", "Greenhouse", "greenhouse-mail.io", "Tech - Large"),
            new Employer("Atlassian", "Workday", "myworkday.com", "Tech - Large"),
            new Employer("Twilio", "Greenhouse", "greenhouse-mail.io", "Tech - Large"),
            new Employer("DoorDash", "Greenhouse", "greenhouse-mail.io", "Tech - Large"),
            new Employer("Robinhood", "Greenhouse", "greenhouse-mail.io", "Fintech"),
            new Employer("Plaid", "Lever", "lever.co", "Fintech"),
            new Employer("Chime", "Ashby", "ashbyhq.com", "Fintech"),
            new Employer("Capital One", "Workday", "myworkday.com", "Finance"),
            new Employer("JPMorgan Chase", "Workday", "myworkday.com", "Finance"),
            new Employer("Goldman Sachs", "Workday", "myworkday.com", "Finance"),
            new Employer("Fidelity Investments", "Workday", "myworkday.com", "Finance"),
            new Employer("Citadel", "Greenhouse", "greenhouse-mail.io", "Finance"),
            new Employer("Jane Street", "Greenhouse", "greenhouse-mail.io", "Finance"),
            new Employer("Progressive Insurance", "Workday", "myworkday.com", "Insurance"),
            new Employer("Nationwide", "iCIMS", "icims.com", "Insurance"),
            new Employer("State Farm", "Workday", "myworkday.com", "Insurance"),
            new Employer("Cardinal Health", "Workday", "myworkday.com", "Healthcare"),
            new Employer("Cleveland Clinic", "iCIMS", "icims.com", "Healthcare"),
            new Employer("Epic Systems", "SmartRecruiters", "smartrecruiters.com", "Healthcare"),
            new Employer("Honda R&D Americas", "SuccessFactors", "successfactors.com", "Manufacturing"),
            new Employer("Procter & Gamble", "Workday", "myworkday.com", "Consumer"),
            new Employer("Abercrombie & Fitch", "Workday", "myworkday.com", "Retail"),
            new Employer("Target", "Workday", "myworkday.com", "Retail"),
            new Employer("Kroger Technology", "Workday", "myworkday.com", "Retail"),
            new Employer("Root Insurance", "Lever", "lever.co", "Insurance"),
            new Employer("Olive AI", "Ashby", "ashbyhq.com", "Startup"),
            new Employer("CoverMyMeds", "Workday", "myworkday.com", "Healthcare"),
            new Employer("Pinterest", "Greenhouse", "greenhouse-mail.io", "Tech - Large"),
            new Employer("Reddit", "Greenhouse", "greenhouse-mail.io", "Tech - Large"),
            new Employer("Asana", "Greenhouse", "greenhouse-mail.io", "Tech - Mid"),
            new Employer("Figma", "Ashby", "ashbyhq.com", "Tech - Mid"),
            new Employer("Notion", "Ashby", "ashbyhq.com", "Tech - Mid"),
            new Employer("Vercel", "Ashby", "ashbyhq.com", "Startup"),
            new Employer("Linear", "Ashby", "ashbyhq.com", "Startup"),
            new Employer("Scale AI", "Lever", "lever.co", "Startup"),
            new Employer("Anduril", "Lever", "lever.co", "Defense"),
            new Employer("Lockheed Martin", "Workday", "myworkday.com", "Defense"),
            new Employer("Battelle", "iCIMS", "icims.com", "Research"),
            new Employer("NetJets", "Jobvite", "jobvite.com", "Aviation"),
            new Employer("Bread Financial", "Workday", "myworkday.com", "Fintech"),
            new Employer("Huntington Bank", "Workday", "myworkday.com", "Finance"));

    static final List<String> POSITIONS = List.of(
            "Software Engineer Intern - Summer 2027",
            "Software Engineering Intern (Summer 2027)",
            "Backend Engineer Intern",
            "Full Stack Software Engineer Intern - Summer 2027",
            "Data Engineer Intern - Summer 2027",
            "Machine Learning Engineer Intern",
            "Site Reliability Engineer Intern",
            "Platform Engineer Intern - Summer 2027",
            "Mobile Engineer Intern (iOS)",
            "Security Engineer Intern - Summer 2027",
            "Cloud Infrastructure Intern",
            "Software Developer Co-op - Fall 2026",
            "Data Analyst Intern - Summer 2027",
            "Product Engineer Intern",
            "Quality Engineering Intern");

    static final List<String> LOCATIONS = List.of(
            "Columbus, OH", "New York, NY", "San Francisco, CA", "Seattle, WA",
            "Austin, TX", "Chicago, IL", "Remote", "Boston, MA", "Atlanta, GA",
            "Cincinnati, OH", "Cleveland, OH", "Denver, CO", "Hybrid - Columbus, OH");

    static final List<String> ASSESSMENT_PLATFORMS = List.of(
            "CodeSignal", "HackerRank", "Codility", "HireVue");
}
