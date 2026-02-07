## Background
Good work done in ../vault-mvp. We got a useful version of the core data
processing demonstrated:
 - Ingest Files
 - Rebuild/validate containers
 - Implement pluggable data processing workflow system (Tasks) to allow
	 enrichment, indexing, etc.

The issue is that we re-wrote (poorly) a lot of framework-provided code and
skipped logs of framework-provided features (logging, DI, etc). We need a
framework that is modern, efficient, opinionated, robust, mature, and has good
ecosystem. Quarkus has all.

I am not a Quarkus expert. I have standard but don't know "The Quarkus Way" to
do things. Myu requirements/advice is subject to scrutiny. Goal is to get what
we need while leveraging momentum/consensus where possible.

I want to combine 2 goals: 1) system design and 2) component docs. The docs need
to be concise and easily read by humans and AIs to clarify this projects
standards/decisions/requirements while referring to official docs as links. We
do not want to re-write external docs. Code samples are also valuable here as
long as they are concise, or we can create examples and link to from markdown.

Each document is a disussion/review point. Many docs will reference each other.
I want to spend time at this level before any implementation plans are built.

Overall product docs can be found in ../pm. These are relevant vision/high level
docs but some implementation details were superceded in ../vault-mvp. We will
need to clarify/correct this repo as most reeliable source. vault-mvp will go
away and pm/ will be updated.

README.md
 - Brief overview
 - Docs index

CLAUDE.md
 - refer to README.md
 - development process (tbd)

dos/Platform.md
 - Quarkus background
 - Profiles (dev,qa,prod,...). Each profile is 
	 - Configs

docs/Architecture.md

Quarkus
 - Config
	 - DI config (ArC)
   - SmallRye Config

 - Logging
 - Event Bus
 - DI
 - Liecycle Events: 
   - @Startup and @Shutdown @ApplicationScoped
	 - Core Services
	 - service cnfig

Logging:
 - SLF4J -> JBoss Log Manger
 - Quarkus Log

Extensions
 - Liquibase
 - Postgres
 - JDBI

DevServices
 - Postgres
 - Object Store
 - Keycloak
 - Databases

Testing
 - profiles
 - no mocking -- alt configs

NFRs:

Tenancy.md
Logging.md
Events.md
Configuration.md
Lifecycle.md
DevServices.md
Database.md
ObjectStore.md
REST.md
Identity.md
Testing.md
DataTypes.md
Deployment.md
Extensions.md
Scheduler.md
Events.md
FileFormats.md
Tasks.md
Extensions.md
 - FileFormats
 - Tasks
 - Events
 - Services

features/
Ingestion.md
Reconstruction.md
RebuildSQL.md

