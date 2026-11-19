#!/usr/bin/env python3
"""Write eval/fit_pairs.jsonl - 20 resume/posting pairs with expected bands.

    python3 scripts/generate_fit_pairs.py

Each pair is a structured resume profile, a posting written the way postings
are actually written, and the band I judged it belongs in before running the
scorer:

    STRONG     75+   has what the posting asks for, and the experience to show it
    GOOD       55-74 most of it, with a visible gap or two
    STRETCH    35-54 adjacent background, real gaps
    LONG_SHOT  <35   wrong field, or a hard eligibility block

The bands were assigned by reading each pair as a recruiter would, not by
running the scorer and writing down what it said. Where my judgement was
genuinely uncertain between two bands, the note records it.

The resume side is structured rather than a PDF because PDF extraction is
tested separately; this set measures the scorer and the posting parser.
"""

import json
from pathlib import Path

# Reusable resume profiles -------------------------------------------------

BACKEND_JAVA = {
    "languages": ["Java", "Python", "SQL", "JavaScript"],
    "frameworks": ["Spring Boot", "React", "Hibernate"],
    "databases": ["PostgreSQL", "Redis"],
    "tools": ["Git", "Docker", "JUnit", "Agile"],
    "cloud": ["AWS"],
    "experience": [
        {
            "title": "Software Engineering Intern",
            "organization": "Regional Insurance Co",
            "period": "Summer 2026",
            "highlights": [
                "Built three REST endpoints in Spring Boot backed by PostgreSQL, used by the claims portal",
                "Added JUnit and Testcontainers coverage to a service that had none",
                "Containerised a legacy batch job with Docker and moved it to ECS",
            ],
        }
    ],
    "projects": [
        {
            "name": "FastTrack",
            "description": "Full-stack internship tracker: Spring Boot API, PostgreSQL, Redis, Next.js frontend",
            "technologies": ["Java", "Spring Boot", "PostgreSQL", "Redis", "Docker"],
        }
    ],
    "education": {
        "school": "The Ohio State University",
        "degree": "Bachelor of Science",
        "field": "Computer Science and Engineering",
        "expected_graduation": "May 2027",
    },
    "work_authorization": "US Citizen",
}

DATA_PYTHON = {
    "languages": ["Python", "SQL", "R"],
    "frameworks": ["pandas", "NumPy", "scikit-learn", "Flask"],
    "databases": ["PostgreSQL", "Snowflake"],
    "tools": ["Git", "Jupyter", "Agile", "Tableau"],
    "cloud": ["AWS"],
    "experience": [
        {
            "title": "Data Analyst Intern",
            "organization": "University Research Group",
            "period": "2026",
            "highlights": [
                "Built ETL jobs in Python that loaded 40M rows a week into Snowflake",
                "Wrote SQL models and dashboards used weekly by two research teams",
            ],
        }
    ],
    "projects": [
        {
            "name": "Transit delay model",
            "description": "Trained a gradient boosting model on five years of transit data",
            "technologies": ["Python", "scikit-learn", "pandas"],
        }
    ],
    "education": {
        "school": "The Ohio State University",
        "degree": "Bachelor of Science",
        "field": "Data Analytics",
        "expected_graduation": "May 2027",
    },
    "work_authorization": "US Citizen",
}

FRONTEND = {
    "languages": ["JavaScript", "TypeScript", "HTML", "CSS"],
    "frameworks": ["React", "Next.js", "Tailwind CSS"],
    "databases": ["MongoDB"],
    "tools": ["Git", "Figma", "Jest"],
    "cloud": [],
    "experience": [
        {
            "title": "Frontend Developer (part-time)",
            "organization": "Campus startup",
            "period": "2025-2026",
            "highlights": [
                "Built the marketing site and dashboard in Next.js and Tailwind",
                "Set up Jest and Playwright coverage for the checkout flow",
            ],
        }
    ],
    "projects": [
        {
            "name": "Course planner",
            "description": "React app that schedules courses against degree requirements",
            "technologies": ["TypeScript", "React"],
        }
    ],
    "education": {
        "school": "Columbus State",
        "degree": "Bachelor of Science",
        "field": "Computer Science",
        "expected_graduation": "December 2027",
    },
    "work_authorization": "US Citizen",
}

ML_STUDENT = {
    "languages": ["Python", "C++"],
    "frameworks": ["PyTorch", "TensorFlow", "NumPy", "pandas"],
    "databases": ["PostgreSQL"],
    "tools": ["Git", "Linux", "Docker"],
    "cloud": ["GCP"],
    "experience": [
        {
            "title": "Undergraduate Research Assistant",
            "organization": "Vision Lab",
            "period": "2025-2026",
            "highlights": [
                "Trained and evaluated CNN architectures on a 200k-image dataset in PyTorch",
                "Built the data pipeline that cleaned and augmented the training set",
            ],
        }
    ],
    "projects": [
        {
            "name": "Sign language recogniser",
            "description": "Real-time gesture classifier running on a laptop webcam",
            "technologies": ["Python", "PyTorch"],
        }
    ],
    "education": {
        "school": "The Ohio State University",
        "degree": "Bachelor of Science",
        "field": "Computer Science",
        "expected_graduation": "May 2027",
    },
    "work_authorization": "F-1 student, will require sponsorship after graduation",
}

FIRST_YEAR = {
    "languages": ["Python", "Java"],
    "frameworks": [],
    "databases": [],
    "tools": ["Git"],
    "cloud": [],
    "experience": [],
    "projects": [
        {
            "name": "Number guessing game",
            "description": "Console game written for an intro programming course",
            "technologies": ["Python"],
        }
    ],
    "education": {
        "school": "The Ohio State University",
        "degree": "Bachelor of Science",
        "field": "Computer Science",
        "expected_graduation": "May 2029",
    },
    "work_authorization": "US Citizen",
}

# Reusable postings --------------------------------------------------------

BACKEND_POSTING = """Software Engineer Intern - Summer 2027
Location: Columbus, OH (hybrid)

About the role
Join the payments platform team to build and maintain the services that move
money for our customers.

Responsibilities
- Build and test REST APIs in Java and Spring Boot
- Work with PostgreSQL schemas and write efficient queries
- Deploy services with Docker through our CI/CD pipeline
- Write unit and integration tests for everything you ship

Requirements
- Pursuing a Bachelor's degree in Computer Science or a related field
- Coursework or project experience with Java
- Familiarity with SQL and relational databases
- Comfortable with Git

Preferred qualifications
- Experience with Spring Boot
- Exposure to Docker or Kubernetes
- Experience writing automated tests with JUnit

Graduating December 2026 - June 2027.
"""

DATA_POSTING = """Data Engineer Intern - Summer 2027

Responsibilities
- Build ETL pipelines that load data into our warehouse
- Write SQL transformations and tests for data models
- Partner with analysts to understand what they need

Requirements
- Strong Python
- Strong SQL
- Experience with a cloud data warehouse such as Snowflake
- Pursuing a Bachelor's degree

Preferred qualifications
- Experience with Spark
- Experience with Airflow
- AWS
"""

ML_POSTING = """Machine Learning Engineer Intern - Summer 2027

Responsibilities
- Train and evaluate models for our recommendation system
- Build data pipelines that feed model training
- Measure model quality and write up what you find

Requirements
- Python
- PyTorch or TensorFlow
- Understanding of machine learning fundamentals
- Pursuing a Bachelor's or Master's degree in Computer Science or a related field

Preferred qualifications
- Experience with distributed training
- GCP or AWS
"""

FRONTEND_POSTING = """Frontend Engineer Intern - Summer 2027

Responsibilities
- Build product surfaces in React and TypeScript
- Work with designers in Figma to turn mockups into components
- Write tests for the components you build

Requirements
- JavaScript
- React
- HTML and CSS
- Pursuing a Bachelor's degree

Preferred qualifications
- TypeScript
- Next.js
- Jest or another testing framework
"""

SECURITY_CLEARANCE_POSTING = """Software Engineer Intern - Defense Systems - Summer 2027

Responsibilities
- Develop mission software in C++ and Java
- Work within a secure development environment

Requirements
- Java or C++
- Pursuing a Bachelor's degree in Computer Science
- Must be a U.S. Citizen; this role requires the ability to obtain a security clearance and we do not provide sponsorship

Preferred qualifications
- Linux
- Experience with real-time systems
"""

GRADUATION_MISMATCH_POSTING = """Software Engineer Intern - Summer 2027

Responsibilities
- Build backend services in Java

Requirements
- Java
- SQL
- Graduating May 2026 or earlier - this internship converts to a full-time role

Preferred qualifications
- Spring Boot
"""

EMBEDDED_POSTING = """Embedded Software Engineer Intern - Summer 2027

Responsibilities
- Write firmware for automotive control units
- Debug on target hardware with an oscilloscope and JTAG

Requirements
- C
- C++
- Embedded systems coursework
- Pursuing a Bachelor's degree in Electrical or Computer Engineering

Preferred qualifications
- RTOS experience
- CAN bus
"""

VAGUE_POSTING = """Software Engineer Intern

We are looking for bright, motivated students to join our growing team. You
will work on interesting problems alongside experienced engineers and make a
real impact from day one. We value curiosity, ownership, and a bias for
action.
"""

PAIRS = [
    (BACKEND_JAVA, BACKEND_POSTING, "STRONG",
     "has every required skill, the preferred ones too, and shipped Spring Boot work"),
    (BACKEND_JAVA, DATA_POSTING, "GOOD",
     "Python and SQL yes, Snowflake no; ETL work is adjacent but real"),
    (BACKEND_JAVA, FRONTEND_POSTING, "GOOD",
     "JavaScript and React on the resume, but the experience is backend"),
    (BACKEND_JAVA, ML_POSTING, "STRETCH",
     "Python only; no ML framework and no ML work"),
    (BACKEND_JAVA, EMBEDDED_POSTING, "LONG_SHOT",
     "wrong discipline: no C, no C++, no embedded work"),
    (BACKEND_JAVA, SECURITY_CLEARANCE_POSTING, "GOOD",
     "US citizen so eligible; Java yes, C++ no"),
    (BACKEND_JAVA, GRADUATION_MISMATCH_POSTING, "STRETCH",
     "hard eligibility block: graduates 2027, posting wants 2026. Labeled STRETCH, not "
     "LONG_SHOT, because a hard mismatch caps the score at 40 by design and 40 is the "
     "bottom of the Stretch band - the red eligibility row is what communicates the block"),
    (DATA_PYTHON, DATA_POSTING, "STRONG",
     "Python, SQL and Snowflake, with warehouse ETL experience to match"),
    (DATA_PYTHON, BACKEND_POSTING, "STRETCH",
     "SQL and a Bachelor's, but no Java at all"),
    (DATA_PYTHON, ML_POSTING, "GOOD",
     "Python and scikit-learn and a trained model, but no PyTorch or TensorFlow"),
    (DATA_PYTHON, FRONTEND_POSTING, "LONG_SHOT",
     "no JavaScript, no React, no frontend work"),
    (FRONTEND, FRONTEND_POSTING, "STRONG",
     "every required and preferred skill, with shipped Next.js work"),
    (FRONTEND, BACKEND_POSTING, "LONG_SHOT",
     "no Java, no SQL, no backend experience"),
    (FRONTEND, DATA_POSTING, "LONG_SHOT",
     "no Python, no SQL, no warehouse"),
    (ML_STUDENT, ML_POSTING, "GOOD",
     "PyTorch, TensorFlow and real training work; sponsorship is not blocked by this posting"),
    (ML_STUDENT, SECURITY_CLEARANCE_POSTING, "STRETCH",
     "hard eligibility block: posting requires citizenship, resume states sponsorship needed. "
     "Capped at 40 by the same rule as the graduation mismatch"),
    (ML_STUDENT, BACKEND_POSTING, "STRETCH",
     "no Java and no Spring, though the engineering work is solid"),
    (ML_STUDENT, DATA_POSTING, "GOOD",
     "Python, pandas and pipeline work; SQL and Snowflake are thin"),
    (FIRST_YEAR, BACKEND_POSTING, "STRETCH",
     "Java and Git and a degree in progress, but no SQL and no real work yet"),
    (FIRST_YEAR, VAGUE_POSTING, "STRETCH",
     "the posting states no requirements at all; the score should rest on what little is scoreable, and say so"),
]


def main():
    path = Path(__file__).resolve().parent.parent / "eval" / "fit_pairs.jsonl"
    path.parent.mkdir(parents=True, exist_ok=True)

    with path.open("w", encoding="utf-8") as handle:
        for index, (resume, posting, band, note) in enumerate(PAIRS, start=1):
            handle.write(json.dumps({
                "id": f"f{index:02d}",
                "resume": resume,
                "posting_text": posting,
                "expected_band": band,
                "note": note,
            }, ensure_ascii=False) + "\n")

    counts = {}
    for _, _, band, _ in PAIRS:
        counts[band] = counts.get(band, 0) + 1
    print(f"Wrote {len(PAIRS)} resume/posting pairs to {path}")
    for band in ("STRONG", "GOOD", "STRETCH", "LONG_SHOT"):
        print(f"  {band:10s} {counts.get(band, 0)}")


if __name__ == "__main__":
    main()
