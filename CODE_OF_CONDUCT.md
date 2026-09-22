# Code of Conduct

## Our Pledge

In the interest of fostering an open and welcoming environment, we as
contributors and maintainers pledge to make participation in our project and
our community a harassment-free experience for everyone, regardless of age, body
size, disability, ethnicity, sex characteristics, gender identity and expression,
level of experience, education, socio-economic status, nationality, personal
appearance, race, religion, or sexual identity and orientation.

## Our Standards

Examples of behavior that contributes to creating a positive environment
include:

* Using welcoming and inclusive language
* Being respectful of differing viewpoints and experiences
* Gracefully accepting constructive criticism
* Focusing on what is best for the community
* Showing empathy towards other community members

Examples of unacceptable behavior by participants include:

* The use of sexualized language or imagery and unwelcome sexual attention or
advances
* Trolling, insulting/derogatory comments, and personal or political attacks
* Public or private harassment
* Publishing others' private information, such as a physical or electronic
address, without explicit permission
* Other conduct which could reasonably be considered inappropriate in a
professional setting

## Standards specific to this project

Zurp is tooling for Meta bug bounty research. That gives several of the standards
above a sharper edge than they have in most projects, and adds a few that are
particular to working with live traffic, disclosed findings, and researcher
credentials.

**Report product vulnerabilities to the bounty program, not to this repository.**
A GitHub issue is public from the moment you file it. If you have found a bug in
a Meta product — including one you found *using* these tools — it goes through
<https://bugbounty.meta.com/>. Issues here are for bugs in Zurp itself: the
extension, the MCP servers, the docs. If you are unsure which you have, treat it
as a product bug and use the program.

**Never put real people's data in this repository.** Not in an issue, a pull
request, a commit message, a test fixture, a screenshot or a log excerpt. In
practice that means no real account identifiers, names, photos, messages or
profile content; no session cookies, `fb_dtsg` values, bearer tokens or other
credentials, including your own; and no HAR files or proxy exports you have not
read through first. Traffic captures are dense with this material and are the
easiest way to leak it by accident.

Use test data instead. Building accounts you are allowed to attack is what FBDL
is for, and a run's identifiers are safe to share. Where you need to show an
identifier's *shape* rather than its value, redact it or invent one — every
example in this repository's documentation is fabricated.

**Do not republish disclosed findings.** SPARTA leads are disclosed to you
personally, under the terms of a private bounty. Reposting one publicly — the
title, the summary, the proof of concept, or a description detailed enough to
reconstruct it — breaks those terms regardless of whether you meant it as a bug
report here. A Zurp bug involving a finding can almost always be described
without the finding: its shape, the field that parsed wrong, the error you got.

**The maintainers here are not the bounty triage team.** They cannot tell you
whether a report is valid, chase a submission, change an award, or explain a
triage decision. Issues and pull requests are not an escalation path for any of
that, and using them as one is the kind of pressure this document asks you not
to apply.

**Compete on findings, not on people.** Researchers using this toolkit are often
looking at the same endpoints. Disparaging another researcher's work, claiming
credit for theirs, or using project spaces to litigate who found what is
unacceptable here, whatever its merits elsewhere.

## Our Responsibilities

Project maintainers are responsible for clarifying the standards of acceptable
behavior and are expected to take appropriate and fair corrective action in
response to any instances of unacceptable behavior.

Project maintainers have the right and responsibility to remove, edit, or
reject comments, commits, code, wiki edits, issues, and other contributions
that are not aligned to this Code of Conduct, or to ban temporarily or
permanently any contributor for other behaviors that they deem inappropriate,
threatening, offensive, or harmful.

## Scope

This Code of Conduct applies within all project spaces, and it also applies when
an individual is representing the project or its community in public spaces.
Examples of representing a project or community include using an official
project e-mail address, posting via an official social media account, or acting
as an appointed representative at an online or offline event. Representation of
a project may be further defined and clarified by project maintainers.

This Code of Conduct also applies outside the project spaces when there is a
reasonable belief that an individual's behavior may have a negative impact on
the project or its community.

It governs the project's own spaces and is separate from the Meta Bug Bounty
program terms, which govern your research itself and your dealings with the
program. Neither document replaces the other, and conduct that breaches both can
be acted on under both.

## Enforcement

Instances of abusive, harassing, or otherwise unacceptable behavior may be
reported by contacting the project team at <opensource-conduct@meta.com>. All
complaints will be reviewed and investigated and will result in a response that
is deemed necessary and appropriate to the circumstances. The project team is
obligated to maintain confidentiality with regard to the reporter of an incident.
Further details of specific enforcement policies may be posted separately.

If a report concerns personal data, credentials, or a non-public finding exposed
in this repository, say so when you file it: that material is removed first and
discussed afterwards. Be aware that anything published to a public repository may
already have been cloned, cached or indexed by the time it comes down, so a
credential exposed here should be treated as compromised and rotated rather than
merely deleted.

Project maintainers who do not follow or enforce the Code of Conduct in good
faith may face temporary or permanent repercussions as determined by other
members of the project's leadership.

## Attribution

This Code of Conduct is adapted from the [Contributor Covenant][homepage], version 1.4,
available at https://www.contributor-covenant.org/version/1/4/code-of-conduct.html

[homepage]: https://www.contributor-covenant.org

For answers to common questions about this code of conduct, see
https://www.contributor-covenant.org/faq
