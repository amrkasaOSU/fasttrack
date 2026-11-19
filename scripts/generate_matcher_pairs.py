#!/usr/bin/env python3
"""Generate eval/matcher_pairs.jsonl - 100 labeled application pairs.

Each record is two (company, position, requisition id) triples as they might
appear in two different emails, plus the truth: are these the same application
or not?

    python3 scripts/generate_matcher_pairs.py

The pairs are written by hand rather than sampled, because the interesting
cases are specific: the same role spelled three ways, two genuinely different
roles at one company, a company name with and without its legal suffix, and
the pairs that sit near the similarity threshold where a wrong answer either
merges two applications or splits one.

Labels are the product decision, not a string-distance intuition:
  * same=true  -> these should be ONE application in the tracker
  * same=false -> these should be TWO
"""

import json
from pathlib import Path

# (company_a, position_a, req_a, company_b, position_b, req_b, same, note)
PAIRS = [
    # ---------------- same application: wording differences ----------------
    ("Stripe", "Software Engineer Intern - Summer 2027", None,
     "Stripe", "Software Engineering Intern (Summer 2027)", None, True,
     "same role, two ATS spellings"),
    ("Stripe", "Software Engineer Intern", None,
     "Stripe, Inc.", "Software Engineer Intern", None, True,
     "legal suffix only"),
    ("Datadog", "Backend Engineer Intern", None,
     "Datadog", "Backend Engineer Intern - Summer 2027", None, True,
     "season suffix added"),
    ("Capital One", "Software Engineer Intern", None,
     "Capital One", "Software Engineer Intern (Req #JR8821)", None, True,
     "requisition appended to the title"),
    ("Snowflake", "Data Engineer Intern", None,
     "Snowflake", "Data Engineering Intern", None, True,
     "engineer vs engineering"),
    ("Palantir", "SWE Intern", None,
     "Palantir", "Software Engineer Intern", None, True,
     "abbreviation"),
    ("Cloudflare", "Site Reliability Engineer Intern", None,
     "Cloudflare", "Site Reliability Engineer Intern - Summer 2027", None, True,
     "season suffix"),
    ("Atlassian", "Full Stack Engineer Intern", None,
     "Atlassian", "Full Stack Software Engineer Intern", None, True,
     "extra word, same role"),
    ("Robinhood", "Software Developer Co-op - Fall 2026", None,
     "Robinhood", "Software Developer Internship Fall 2026", None, True,
     "co-op vs internship"),
    ("Plaid", "Machine Learning Engineer Intern", None,
     "Plaid", "ML Engineer Intern", None, True,
     "ML abbreviation"),
    ("Chime", "Security Engineer Intern", None,
     "Chime", "Security Engineer Intern 2027", None, True,
     "year appended"),
    ("Figma", "Product Engineer Intern", None,
     "Figma", "Product Engineer Intern (Summer)", None, True,
     "season in brackets"),
    ("Notion", "Backend Engineer Intern", None,
     "Notion ", " Backend Engineer Intern ", None, True,
     "whitespace only"),
    ("Target", "Data Analyst Intern", None,
     "Target Corporation", "Data Analyst Intern", None, True,
     "corporation suffix"),
    ("Kroger Technology", "Software Engineer Intern", None,
     "Kroger Technology", "Software Engineer Intern - Summer 2027", None, True,
     "season suffix"),
    ("Nationwide", "Platform Engineer Intern", None,
     "Nationwide", "Platform Engineering Intern", None, True,
     "engineering variant"),
    ("Cardinal Health", "Software Engineer Intern", None,
     "Cardinal Health", "Software Engineer Intern - Columbus, OH", None, True,
     "location appended"),
    ("Honda R&D Americas", "Embedded Software Intern", None,
     "Honda R&D Americas", "Embedded Software Engineer Intern", None, True,
     "engineer added"),
    ("Pinterest", "Mobile Engineer Intern (iOS)", None,
     "Pinterest", "Mobile Engineer Intern iOS", None, True,
     "punctuation only"),
    ("Reddit", "Software Engineer Intern", None,
     "reddit", "software engineer intern", None, True,
     "case only"),

    # ---------------- same application: requisition id decides -------------
    ("Workday Customer", "Software Engineer Intern", "JR554433",
     "Workday Customer", "Engineering Intern - Platform", "JR554433", True,
     "same requisition, titles differ wildly"),
    ("Goldman Sachs", "Technology Analyst Intern", "GS2027-88",
     "Goldman Sachs", "Summer Analyst - Engineering", "GS2027-88", True,
     "same requisition, different marketing title"),
    ("JPMorgan Chase", "Software Engineer Program - Summer", "210512345",
     "JPMorgan Chase", "SEP Intern", "210512345", True,
     "same requisition, acronym"),
    ("Lockheed Martin", "Software Engineer Intern", "LM-99812",
     "Lockheed Martin", "Software Engineer Intern - Clearance Required", "LM-99812", True,
     "same requisition, qualifier added"),
    ("Battelle", "Cyber Research Intern", "BAT-4411",
     "Battelle", "Cybersecurity Research Internship", "BAT-4411", True,
     "same requisition, reworded"),

    # ---------------- different: different role, same company --------------
    ("Stripe", "Software Engineer Intern", None,
     "Stripe", "Product Design Intern", None, False,
     "engineering vs design"),
    ("Datadog", "Data Engineer Intern", None,
     "Datadog", "Site Reliability Engineer Intern", None, False,
     "two engineering disciplines"),
    ("Capital One", "Software Engineer Intern", None,
     "Capital One", "Data Analyst Intern", None, False,
     "engineer vs analyst"),
    ("Snowflake", "Machine Learning Engineer Intern", None,
     "Snowflake", "Technical Writer Intern", None, False,
     "unrelated roles"),
    ("Palantir", "Backend Engineer Intern", None,
     "Palantir", "Deployment Strategist Intern", None, False,
     "unrelated roles"),
    ("Atlassian", "Security Engineer Intern", None,
     "Atlassian", "Marketing Intern", None, False,
     "unrelated roles"),
    ("Target", "Software Engineer Intern", None,
     "Target", "Supply Chain Intern", None, False,
     "unrelated roles"),
    ("Pinterest", "Mobile Engineer Intern (iOS)", None,
     "Pinterest", "Mobile Engineer Intern (Android)", None, False,
     "different platform, genuinely two postings"),
    ("Cloudflare", "Software Engineer Intern - Summer 2027", None,
     "Cloudflare", "Software Engineer Intern - Fall 2027", None, False,
     "different season, two postings"),
    ("Reddit", "Data Scientist Intern", None,
     "Reddit", "Data Engineer Intern", None, False,
     "scientist vs engineer"),

    # ---------------- different: same role, different company --------------
    ("Stripe", "Software Engineer Intern", None,
     "Datadog", "Software Engineer Intern", None, False,
     "same role, different company"),
    ("Chime", "Backend Engineer Intern", None,
     "Plaid", "Backend Engineer Intern", None, False,
     "same role, different fintech"),
    ("Target", "Data Analyst Intern", None,
     "Kroger Technology", "Data Analyst Intern", None, False,
     "same role, different retailer"),
    ("Nationwide", "Software Engineer Intern", None,
     "Progressive Insurance", "Software Engineer Intern", None, False,
     "same role, different insurer"),
    ("Goldman Sachs", "Technology Analyst Intern", None,
     "JPMorgan Chase", "Technology Analyst Intern", None, False,
     "same role, different bank"),

    # ---------------- different: requisition ids disagree ------------------
    ("Workday Customer", "Software Engineer Intern", "JR554433",
     "Workday Customer", "Software Engineer Intern", "JR998877", False,
     "same title, two different postings"),
    ("Capital One", "Software Engineer Intern", "R176050",
     "Capital One", "Software Engineer Intern", "R176051", False,
     "adjacent requisition ids are still different postings"),
    ("Lockheed Martin", "Software Engineer Intern", "LM-99812",
     "Lockheed Martin", "Software Engineer Intern", "LM-10045", False,
     "two locations, two requisitions"),

    # ---------------- near the line: deliberately hard ---------------------
    ("Snowflake", "Data Engineer Intern", None,
     "Snowflake", "Data Analyst Intern", None, False,
     "two tokens shared, different job"),
    ("Stripe", "Software Engineer Intern", None,
     "Stripe", "Software Engineer Intern II", None, True,
     "level suffix, same posting"),
    ("Figma", "Design Engineer Intern", None,
     "Figma", "Product Design Intern", None, False,
     "design in both, different roles"),
    ("Scale AI", "Machine Learning Intern", None,
     "Scale AI", "Machine Learning Engineer Intern", None, True,
     "engineer implied"),
    ("Anduril", "Embedded Systems Intern", None,
     "Anduril", "Embedded Software Intern", None, False,
     "systems vs software"),
    ("Root Insurance", "Backend Engineer Intern", None,
     "Root Insurance", "Backend Engineer", None, True,
     "intern dropped from the title"),
    ("Bread Financial", "Software Engineer Intern", None,
     "Bread Financial", "Senior Software Engineer", None, False,
     "intern vs senior, not the same job"),
    ("Huntington Bank", "Data Engineer Intern", None,
     "Huntington Bank", "Data Engineering Co-op", None, True,
     "co-op spelling of the same programme"),
]

# Fill out to 100 with systematic variants that exercise normalization.
COMPANIES = ["Stripe", "Datadog", "Snowflake", "Capital One", "Figma",
             "Pinterest", "Target", "Nationwide", "Plaid", "Reddit"]
ROLE_PAIRS_SAME = [
    ("Software Engineer Intern", "Software Engineer Intern - Summer 2027"),
    ("Data Engineer Intern", "Data Engineer Internship"),
    ("Backend Engineer Intern", "Back End Engineer Intern"),
    ("Security Engineer Intern", "Security Engineering Intern"),
    ("Platform Engineer Intern", "Platform Engineer Intern (2027)"),
]
ROLE_PAIRS_DIFFERENT = [
    ("Software Engineer Intern", "Hardware Engineer Intern"),
    ("Data Engineer Intern", "Business Analyst Intern"),
    ("Backend Engineer Intern", "Frontend Engineer Intern"),
    ("Security Engineer Intern", "Sales Development Intern"),
    ("Platform Engineer Intern", "Finance Intern"),
]


def build():
    records = []
    for pair in PAIRS:
        records.append(pair)

    index = 0
    while len(records) < 100:
        company = COMPANIES[index % len(COMPANIES)]
        if index % 2 == 0:
            a, b = ROLE_PAIRS_SAME[(index // 2) % len(ROLE_PAIRS_SAME)]
            records.append((company, a, None, company, b, None, True,
                            "generated variant: wording difference"))
        else:
            a, b = ROLE_PAIRS_DIFFERENT[(index // 2) % len(ROLE_PAIRS_DIFFERENT)]
            records.append((company, a, None, company, b, None, False,
                            "generated variant: different discipline"))
        index += 1

    out = []
    for i, (ca, pa, ra, cb, pb, rb, same, note) in enumerate(records[:100], start=1):
        out.append({
            "id": f"p{i:03d}",
            "a": {"company": ca, "position": pa, "requisition_id": ra},
            "b": {"company": cb, "position": pb, "requisition_id": rb},
            "same": same,
            "note": note,
        })
    return out


def main():
    path = Path(__file__).resolve().parent.parent / "eval" / "matcher_pairs.jsonl"
    path.parent.mkdir(parents=True, exist_ok=True)
    records = build()
    with path.open("w", encoding="utf-8") as handle:
        for record in records:
            handle.write(json.dumps(record, ensure_ascii=False) + "\n")

    same = sum(1 for r in records if r["same"])
    print(f"Wrote {len(records)} labeled pairs to {path}")
    print(f"  same application:      {same}")
    print(f"  different application: {len(records) - same}")


if __name__ == "__main__":
    main()
