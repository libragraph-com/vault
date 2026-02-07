I want to initialize the vault project code with design from docs/*.md as the requirements.

I am fine with "hello world" level stuff" but we need to have at the end:

Persistent Dockers for all services:
 - Postgres (with schema from research)
 - MinIO
 - KeyCloak (not integrated but provisioned)
 - Vault service with diagnostic endpoints

I am on WSL and have installed Docker Desktop and Java25 but not much else.

I need to be able to connect to Postres and Minio. For now that will be just blank data but I will need to run integration tests into these systems to inspect data.

We can assume/demand Java 25+.

We need documentation for how to set up a development environment. Can assume Linux+WSL for now.

We need config files and proiles for test (ephemeral) and dev (persistent services).

Ask questions or reset expectations as needed to create a plan.

