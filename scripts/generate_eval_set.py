#!/usr/bin/env python3
"""Generate the labeled classifier evaluation set at eval/emails.jsonl.

Every record is synthetic: invented message text against real company names.
No real email is read, used or committed - see docs/privacy.md.

The generator is seeded, so re-running it reproduces the file byte for byte:

    python3 scripts/generate_eval_set.py

Design notes
------------
The set is deliberately adversarial in the places this classifier is likely to
fail, because an evaluation that only contains easy mail measures nothing:

* rejections that open with "Thank you for your interest"
* confirmations that mention interviews in passing
* job alerts that name a company the candidate really applied to, and use the
  word "application"
* assessment invitations phrased as "next steps"
* recruiter outreach that reads like an interview request
* portal/account mail that arrives in the middle of a live application

`expect_prefilter` is labeled independently of `category`: an email can be
genuinely job-related and still be something the prefilter should drop.
"""

import json
import random
from pathlib import Path

SEED = 20260216
TARGET_PER_CATEGORY = {
    "APPLICATION_CONFIRMATION": 40,
    "REJECTION": 45,
    "ASSESSMENT_INVITE": 20,
    "INTERVIEW_REQUEST": 18,
    "INTERVIEW_SCHEDULED": 12,
    "OFFER": 8,
    "RECRUITER_OUTREACH": 15,
    "POSITION_CANCELLED": 7,
    "SURVEY_OR_ACCOUNT": 15,
    "NOT_JOB_RELATED": 20,
}

COMPANIES = [
    ("Stripe", "greenhouse-mail.io", "Greenhouse"),
    ("Datadog", "greenhouse-mail.io", "Greenhouse"),
    ("Snowflake", "myworkday.com", "Workday"),
    ("Palantir", "lever.co", "Lever"),
    ("Cloudflare", "greenhouse-mail.io", "Greenhouse"),
    ("Atlassian", "myworkday.com", "Workday"),
    ("Capital One", "myworkday.com", "Workday"),
    ("JPMorgan Chase", "myworkday.com", "Workday"),
    ("Goldman Sachs", "myworkday.com", "Workday"),
    ("Robinhood", "greenhouse-mail.io", "Greenhouse"),
    ("Plaid", "lever.co", "Lever"),
    ("Chime", "ashbyhq.com", "Ashby"),
    ("Figma", "ashbyhq.com", "Ashby"),
    ("Notion", "ashbyhq.com", "Ashby"),
    ("Pinterest", "greenhouse-mail.io", "Greenhouse"),
    ("Reddit", "greenhouse-mail.io", "Greenhouse"),
    ("Progressive Insurance", "myworkday.com", "Workday"),
    ("Nationwide", "icims.com", "iCIMS"),
    ("Cardinal Health", "myworkday.com", "Workday"),
    ("Cleveland Clinic", "icims.com", "iCIMS"),
    ("Honda R&D Americas", "successfactors.com", "SuccessFactors"),
    ("Target", "myworkday.com", "Workday"),
    ("Kroger Technology", "myworkday.com", "Workday"),
    ("Root Insurance", "lever.co", "Lever"),
    ("Scale AI", "lever.co", "Lever"),
    ("Anduril", "lever.co", "Lever"),
    ("Lockheed Martin", "myworkday.com", "Workday"),
    ("Battelle", "icims.com", "iCIMS"),
    ("Huntington Bank", "myworkday.com", "Workday"),
    ("Bread Financial", "myworkday.com", "Workday"),
]

ROLES = [
    "Software Engineer Intern - Summer 2027",
    "Software Engineering Intern (Summer 2027)",
    "Backend Engineer Intern",
    "Full Stack Engineer Intern - Summer 2027",
    "Data Engineer Intern - Summer 2027",
    "Machine Learning Engineer Intern",
    "Site Reliability Engineer Intern",
    "Platform Engineer Intern - Summer 2027",
    "Security Engineer Intern - Summer 2027",
    "Software Developer Co-op - Fall 2026",
    "Data Analyst Intern - Summer 2027",
    "Mobile Engineer Intern (iOS)",
]

PLATFORMS = ["CodeSignal", "HackerRank", "Codility", "HireVue", "Karat"]
CANDIDATE = "Amr"


def req(rng):
    return f"JR{rng.randint(100000, 999999)}"


def when(rng):
    day = rng.choice(["Monday", "Tuesday", "Wednesday", "Thursday", "Friday"])
    date = f"{rng.choice(['March', 'April', 'February'])} {rng.randint(3, 27)}, 2027"
    hour = rng.choice(["9:00 AM", "10:30 AM", "1:00 PM", "2:30 PM", "4:00 PM"])
    return day, date, hour


# --------------------------------------------------------------------------
# Templates. Each returns (sender_local, sender_name, subject, body, note).
# `note` records why a case is in the set; "tricky: ..." marks the hard ones.
# --------------------------------------------------------------------------

def application_confirmation(rng, company, domain, ats, role):
    r = req(rng)
    variants = [
        (
            "no-reply", f"{company} Careers",
            f"Thank you for applying to {company}",
            f"Hi {CANDIDATE},\n\nThank you for applying to the {role} position at {company}. "
            f"We have received your application (requisition {r}) and our recruiting team is "
            f"reviewing it.\n\nIf your background is a match, a member of the team will be in "
            f"touch about next steps.\n\nBest regards,\n{company} Talent Acquisition",
            "plain confirmation",
        ),
        (
            "careers", f"{company} Recruiting",
            f"We received your application - {role}",
            f"Hello {CANDIDATE},\n\nThis is confirmation that your application for {role} "
            f"({r}) has been received. Candidates selected for interviews will be contacted "
            f"directly by a recruiter.\n\nYou can check the status of your application at any "
            f"time in our candidate portal.\n\n- {company}",
            "tricky: mentions interviews in passing but is only a confirmation",
        ),
        (
            "notifications", f"{company} Talent",
            f"Your application to {company} was submitted",
            f"Hi {CANDIDATE},\n\nYour application for the {role} role was successfully "
            f"submitted on our careers site. No further action is needed right now.\n\n"
            f"Thanks for your interest in {company}.",
            "plain confirmation",
        ),
        (
            "no-reply", f"{ats} on behalf of {company}",
            f"Application received: {role}",
            f"Dear {CANDIDATE},\n\nThank you for your interest in {company}. We have received "
            f"your application for {role}. Our team reviews every application and will reach "
            f"out if there is a fit.\n\nThis mailbox is not monitored.",
            "tricky: opens with 'thank you for your interest', which also opens rejections",
        ),
        (
            "talent", f"{company} University Recruiting",
            f"{company} - application confirmation ({r})",
            f"Hi {CANDIDATE},\n\nWe've got your application for {role}. Our university "
            f"recruiting team begins reviewing applications in waves, and we expect to "
            f"complete the first round of reviews in the next three weeks.\n\n"
            f"Thanks again for applying.",
            "plain confirmation",
        ),
    ]
    return rng.choice(variants)


def rejection(rng, company, domain, ats, role):
    variants = [
        (
            "no-reply", f"{company} Recruiting",
            f"Your application to {company}",
            f"Hi {CANDIDATE},\n\nThank you for your interest in the {role} position at "
            f"{company} and for taking the time to apply.\n\nAfter careful review, we have "
            f"decided to move forward with other candidates whose experience more closely "
            f"matches the needs of the role. We appreciate the time you invested and "
            f"encourage you to apply for future openings.\n\nBest wishes,\n{company} Talent Team",
            "tricky: polite rejection that opens exactly like a confirmation",
        ),
        (
            "careers", f"{company} Careers",
            f"Update on your application - {role}",
            f"Dear {CANDIDATE},\n\nWe regret to inform you that we will not be moving forward "
            f"with your application for {role} at this time.\n\nWe received an exceptionally "
            f"high number of applications this season. Please do keep an eye on our careers "
            f"page.\n\nSincerely,\n{company}",
            "explicit rejection",
        ),
        (
            "no-reply", f"{ats}",
            f"{company}: thank you for your application",
            f"Hello {CANDIDATE},\n\nThank you again for your interest in {company}. "
            f"Unfortunately, we have decided not to proceed with your candidacy for the "
            f"{role} role.\n\nThis decision was not an easy one. We wish you the very best "
            f"in your search.",
            "tricky: the word 'thank you' appears three times before the decision",
        ),
        (
            "university", f"{company} University Recruiting",
            f"Your {company} internship application",
            f"Hi {CANDIDATE},\n\nOur team has completed its review of applications for "
            f"{role}. While your background is impressive, you have not been selected to "
            f"continue in the process this cycle.\n\nWe hope you will consider applying "
            f"again next year.",
            "tricky: compliments before the rejection, never uses the word 'reject'",
        ),
        (
            "no-reply", f"{company} Hiring",
            f"Regarding your candidacy at {company}",
            f"{CANDIDATE},\n\nThe {role} position has been filled. Your application is no "
            f"longer under consideration.\n\nThank you for your time.",
            "rejection where the role was filled by someone else",
        ),
        (
            "talent", f"{company} Talent Acquisition",
            f"An update from {company}",
            f"Hi {CANDIDATE},\n\nThanks for interviewing with our team for {role}. "
            f"After speaking with all candidates, we have decided to move ahead with "
            f"another applicant.\n\nWe genuinely enjoyed our conversation and hope to stay "
            f"in touch.",
            "tricky: rejection after an interview, warm tone",
        ),
    ]
    return rng.choice(variants)


def assessment_invite(rng, company, domain, ats, role):
    platform = rng.choice(PLATFORMS)
    days = rng.randint(3, 7)
    variants = [
        (
            "no-reply", f"{platform}",
            f"{company} invites you to complete an online assessment",
            f"Hi {CANDIDATE},\n\n{company} has invited you to complete an online assessment "
            f"for the {role} position.\n\nThe assessment takes approximately 70 minutes and "
            f"must be completed within {days} days. Start it here: "
            f"https://app.{platform.lower()}.com/invite/xyz\n\nGood luck!",
            "standard assessment invite",
        ),
        (
            "recruiting", f"{company} Recruiting",
            f"Next steps for your {company} application",
            f"Hi {CANDIDATE},\n\nThanks for applying to {role}. As the next step in our "
            f"process, please complete a {platform} coding challenge. You will have {days} "
            f"days from today to finish it.\n\nThe challenge consists of two algorithm "
            f"questions and one short debugging exercise.",
            "tricky: phrased as 'next steps', which also appears in interview invitations",
        ),
        (
            "no-reply", f"{ats} on behalf of {company}",
            f"Action required: technical assessment for {role}",
            f"Dear {CANDIDATE},\n\nYour application has progressed to the technical "
            f"assessment stage. Please complete the {platform} assessment by the deadline "
            f"shown in the portal.\n\nIf you have accommodations needs, reply to this "
            f"message.",
            "assessment with a portal deadline",
        ),
        (
            "interviews", f"{company} Talent",
            f"Your {platform} take-home for {company}",
            f"Hi {CANDIDATE},\n\nWe'd love to see how you approach a realistic problem. "
            f"Attached is the take-home assignment for {role}. Please submit within {days} "
            f"days.\n\nWe expect this to take no more than four hours.",
            "take-home assignment variant",
        ),
    ]
    return rng.choice(variants)


def interview_request(rng, company, domain, ats, role):
    variants = [
        (
            "recruiting", f"{company} Recruiting",
            f"Interview invitation - {role} at {company}",
            f"Hi {CANDIDATE},\n\nWe were impressed by your application for {role} and would "
            f"like to invite you to a 45-minute technical interview.\n\nPlease use the link "
            f"below to choose a time that works for you over the next two weeks.",
            "clear interview invitation",
        ),
        (
            "talent", f"{company} Talent Acquisition",
            f"Are you available to chat about {role}?",
            f"Hi {CANDIDATE},\n\nI'm the recruiter for {role} at {company}. I reviewed your "
            f"application and would love to set up a 30-minute call to tell you more about "
            f"the team.\n\nCould you share your availability for later this week?",
            "tricky: a recruiter writing, but about an application the candidate submitted",
        ),
        (
            "no-reply", f"{ats} on behalf of {company}",
            f"Next steps: interview for {role}",
            f"Hello {CANDIDATE},\n\nCongratulations - your application has moved to the "
            f"interview stage. Please select an interview slot in the candidate portal "
            f"within three business days.",
            "interview request through the ATS",
        ),
        (
            "recruiting", f"{company} University Recruiting",
            f"Phone screen for your {company} application",
            f"Hi {CANDIDATE},\n\nThanks for your patience. We'd like to schedule a phone "
            f"screen for the {role} position. The call is 30 minutes with one of our "
            f"engineers.\n\nWhat times work for you next week?",
            "phone screen request",
        ),
    ]
    return rng.choice(variants)


def interview_scheduled(rng, company, domain, ats, role):
    day, date, hour = when(rng)
    variants = [
        (
            "interviews", f"{company} Interview Scheduling",
            f"Confirmed: your interview with {company} on {date}",
            f"Hi {CANDIDATE},\n\nYour interview for {role} is confirmed for {day}, {date} at "
            f"{hour} Eastern Time.\n\nInterviewers: two engineers from the platform team.\n"
            f"Format: 60 minutes, one coding exercise and one system design discussion.\n\n"
            f"A calendar invitation with the video link has been sent separately.",
            "confirmed interview with date and time",
        ),
        (
            "no-reply", f"{ats} Scheduling",
            f"Interview confirmation - {role}",
            f"Dear {CANDIDATE},\n\nThis message confirms your upcoming interview.\n\n"
            f"Date: {date}\nTime: {hour} ET\nDuration: 45 minutes\nLocation: Video call\n\n"
            f"Please let us know at least 24 hours in advance if you need to reschedule.",
            "scheduling confirmation from the ATS",
        ),
        (
            "recruiting", f"{company} Recruiting",
            f"Your interview is scheduled for {date}",
            f"Hi {CANDIDATE},\n\nGreat news - we have scheduled your final round for {role} "
            f"on {day}, {date} at {hour}. You will meet three team members back to back.\n\n"
            f"Let me know if anything changes.",
            "final round scheduled",
        ),
    ]
    return rng.choice(variants)


def offer(rng, company, domain, ats, role):
    variants = [
        (
            "recruiting", f"{company} Recruiting",
            f"Your offer from {company}",
            f"Hi {CANDIDATE},\n\nWe are pleased to offer you the {role} position at "
            f"{company} for Summer 2027. Your offer letter, including compensation and "
            f"start date, is attached.\n\nPlease let us know your decision by the date in "
            f"the letter. Congratulations!",
            "clear offer",
        ),
        (
            "talent", f"{company} Talent",
            f"Offer of employment - {role}",
            f"Dear {CANDIDATE},\n\nFollowing your interviews, the team unanimously agreed "
            f"you would be a great addition. We would like to extend an offer for the "
            f"{role} position.\n\nI'll call this afternoon to walk through the details.",
            "offer that follows interviews",
        ),
        (
            "no-reply", f"{ats} on behalf of {company}",
            f"Action required: review your offer",
            f"Hello {CANDIDATE},\n\nAn offer for {role} is ready for your review in the "
            f"candidate portal. Unfortunately the start date cannot be moved later than "
            f"June 1, but everything else is negotiable.\n\nPlease sign electronically.",
            "tricky: an offer that contains the word 'unfortunately'",
        ),
    ]
    return rng.choice(variants)


def recruiter_outreach(rng, company, domain, ats, role):
    variants = [
        (
            "sourcing", f"Jordan at {company}",
            f"{role} opportunity at {company}",
            f"Hi {CANDIDATE},\n\nI came across your profile and was impressed by your "
            f"backend work. I'm recruiting for {role} at {company} and thought you might "
            f"be a great fit.\n\nWould you be interested in a quick chat this week?",
            "cold outreach from a recruiter",
        ),
        (
            "talent", "Priya Raman",
            f"Exploring opportunities at {company}?",
            f"Hi {CANDIDATE},\n\nI'm a technical recruiter at {company}. Your projects "
            f"caught my eye, and we have several internship openings for Summer 2027 "
            f"including {role}.\n\nAre you open to hearing more?",
            "tricky: outreach that reads like an interview request",
        ),
        (
            "hiring", f"{company} Talent Partner",
            f"Quick question about your summer plans",
            f"Hi {CANDIDATE},\n\nFound your profile through our university partnership. "
            f"We're hiring interns for {role} and I'd love to tell you about the team. "
            f"No application needed yet - just a conversation.\n\nInterested?",
            "outreach with no application behind it",
        ),
    ]
    return rng.choice(variants)


def position_cancelled(rng, company, domain, ats, role):
    variants = [
        (
            "recruiting", f"{company} Recruiting",
            f"Update: {role} position",
            f"Hi {CANDIDATE},\n\nI wanted to let you know directly that the {role} "
            f"requisition has been closed due to a change in our hiring plan for next "
            f"year. This is not a reflection of your candidacy.\n\nYour profile will stay "
            f"in our system, and I will reach out if the role reopens.",
            "tricky: cancellation that could be read as a rejection",
        ),
        (
            "no-reply", f"{ats} on behalf of {company}",
            f"{role} - requisition closed",
            f"Dear {CANDIDATE},\n\nThe position you applied for ({role}) is no longer "
            f"being filled. Hiring for this role has been paused indefinitely.\n\n"
            f"We encourage you to apply to other openings.",
            "role closed through the ATS",
        ),
    ]
    return rng.choice(variants)


def survey_or_account(rng, company, domain, ats, role):
    variants = [
        (
            "no-reply", f"{ats}",
            f"Complete your {company} candidate profile",
            f"Hello {CANDIDATE},\n\nTo finish setting up your candidate account for "
            f"{company}, please create your account password using the link below.\n\n"
            f"This link expires in 24 hours.",
            "account creation at an ATS",
        ),
        (
            "no-reply", f"{company} Careers",
            "Voluntary self-identification",
            f"Hi {CANDIDATE},\n\n{company} invites all applicants to complete a voluntary "
            f"self-identification form. Your responses are confidential and are not shared "
            f"with the hiring team, and they have no effect on your application.",
            "tricky: EEO survey that arrives during a live application",
        ),
        (
            "no-reply", f"{ats} Support",
            "Password reset request",
            f"Hello,\n\nA password reset was requested for your candidate account. "
            f"If this wasn't you, ignore this message.\n\nReset link: https://example/reset",
            "account mail",
        ),
        (
            "surveys", f"{company} Candidate Experience",
            "How was your application experience?",
            f"Hi {CANDIDATE},\n\nYou recently applied to {company}. We'd love two minutes "
            f"of your time to tell us how the application process went. This survey does "
            f"not affect your candidacy.",
            "candidate experience survey",
        ),
    ]
    return rng.choice(variants)


def not_job_related(rng, company, domain, ats, role):
    other_role = rng.choice(ROLES)
    variants = [
        (
            "jobalerts-noreply", "LinkedIn Job Alerts",
            f"{rng.randint(8, 30)} new jobs for \"software engineer intern\"",
            f"Your job alert for software engineer intern\n\n{company} - {role}\n"
            f"Another Company - {other_role}\n\nSee all new jobs matching your search.",
            "tricky: job alert naming a company the candidate applied to",
        ),
        (
            "jobs-noreply", "Handshake",
            f"Jobs for you: {other_role} and more",
            f"Hi {CANDIDATE},\n\nBased on your profile, here are jobs we think you'll like. "
            f"Complete your application for {company} before the deadline this Friday.\n\n"
            f"View all recommended jobs.",
            "tricky: alert containing the phrase 'complete your application'",
        ),
        (
            "news", "ACM Student Chapter",
            "This week in tech: our newsletter",
            "Hello members,\n\nThis week's newsletter covers the upcoming hackathon, a "
            "tech talk on distributed systems, and internship application tips.\n\n"
            "Unsubscribe at any time.",
            "newsletter",
        ),
        (
            "events", "University Career Fair",
            "Register now: Spring Career Fair",
            f"Join us for the Spring Career Fair featuring {company} and 60 other "
            f"employers. Register now to reserve your spot. Bring copies of your resume.",
            "event registration",
        ),
        (
            "notifications", "LinkedIn",
            "Someone viewed your profile",
            "Your profile was viewed by a recruiter at a company in your network. "
            "See who viewed your profile with Premium - free trial available.",
            "networking notification",
        ),
        (
            "no-reply", "GitHub",
            "Security alert: new sign-in",
            "We noticed a new sign-in to your GitHub account from a new device. "
            "If this was you, no action is needed.",
            "unrelated account mail",
        ),
    ]
    return rng.choice(variants)


BUILDERS = {
    "APPLICATION_CONFIRMATION": application_confirmation,
    "REJECTION": rejection,
    "ASSESSMENT_INVITE": assessment_invite,
    "INTERVIEW_REQUEST": interview_request,
    "INTERVIEW_SCHEDULED": interview_scheduled,
    "OFFER": offer,
    "RECRUITER_OUTREACH": recruiter_outreach,
    "POSITION_CANCELLED": position_cancelled,
    "SURVEY_OR_ACCOUNT": survey_or_account,
    "NOT_JOB_RELATED": not_job_related,
}

# Categories whose mail the prefilter is expected to drop before any LLM call.
DROP_CATEGORIES = {"NOT_JOB_RELATED"}

# Individual cases that are job-adjacent but still cannot change an
# application's status, so the prefilter should drop them even though their
# category is not NOT_JOB_RELATED. A password reset is account plumbing: no
# classifier needs to see it.
DROP_NOTES = {"account mail"}


def build():
    rng = random.Random(SEED)
    records = []
    index = 0

    for category, count in TARGET_PER_CATEGORY.items():
        builder = BUILDERS[category]
        for _ in range(count):
            company, domain, ats = rng.choice(COMPANIES)
            role = rng.choice(ROLES)
            local, sender_name, subject, body, note = builder(rng, company, domain, ats, role)

            if category == "NOT_JOB_RELATED":
                sender_domain = rng.choice(
                    ["linkedin.com", "joinhandshake.com", "acm.org", "university.edu", "github.com"]
                )
            elif category == "RECRUITER_OUTREACH":
                sender_domain = rng.choice([domain, company.lower().replace(" ", "") + ".com"])
            else:
                sender_domain = domain

            index += 1
            records.append(
                {
                    "id": f"e{index:03d}",
                    "category": category,
                    "company": None if category == "NOT_JOB_RELATED" else company,
                    "position_title": None if category in ("NOT_JOB_RELATED", "SURVEY_OR_ACCOUNT") else role,
                    "sender": f"{local}@{sender_domain}",
                    "sender_name": sender_name,
                    "subject": subject,
                    "body": body,
                    "ats": ats,
                    "expect_prefilter": "DROP"
                    if category in DROP_CATEGORIES or note in DROP_NOTES
                    else "KEEP",
                    "note": note,
                }
            )

    rng.shuffle(records)
    for position, record in enumerate(records, start=1):
        record["id"] = f"e{position:03d}"
    return records


def main():
    out = Path(__file__).resolve().parent.parent / "eval" / "emails.jsonl"
    out.parent.mkdir(parents=True, exist_ok=True)
    records = build()
    with out.open("w", encoding="utf-8") as handle:
        for record in records:
            handle.write(json.dumps(record, ensure_ascii=False) + "\n")

    counts = {}
    for record in records:
        counts[record["category"]] = counts.get(record["category"], 0) + 1
    print(f"Wrote {len(records)} labeled emails to {out}")
    for category in sorted(counts):
        print(f"  {category:26s} {counts[category]:3d}")
    tricky = sum(1 for r in records if r["note"].startswith("tricky"))
    print(f"  {'(of which marked tricky)':26s} {tricky:3d}")


if __name__ == "__main__":
    main()
