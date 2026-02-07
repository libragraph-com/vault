## App Configuration

Sets up process/application to allow the same code to talk to different external
systems and tune params/choices for each.

sample configuration:
  /opt/myapp/
    ├── myapp.jar
        ├── application.properties        # Base config
    └── config/
        ├── application-dev.properties    # Dev overrides
        ├── application-qa.properties     # QA overrides
        └── application-prod.properties   # Prod overrides

  cd /opt/myapp
  java -Dquarkus.profile=qa -jar myapp.jar

  Quarkus automatically checks ./config/ directory (relative to where you run the JAR) and applies the profile file.

  Or specify custom location:

  java -Dquarkus.config.locations=/etc/myapp/config \
       -Dquarkus.profile=qa \
       -jar myapp.jar

Env overrides (dev plus deployed)

Any pluggable implementations to ArC are set up in config profile:
objectstore.impl=FileSystemObjectStore

Each profile specifies a KeyCloak Realm

## Users/Identiies
Users are defined in a global namspace (allowing SaaS/global identities) but
are tracked locally in a KeyCloak service that does user Auth upstream of the
application. 

### Org
KeyCloak has Users, Groups, Roles. Orgs don't define/create Users (which come from Keycloak)
but they store which users have Roles inside a given org. 

## Tokens
Users can create tokens that allow limited access on their behalf.
Tokens can be scope to speciic regions (org, tenant, share).

JWTs are sent to services and serves as the security principal for the request.

## Partitioning
Inside a given process/application there are Organizations, Tenants, and
f Sandboxes

### Tenant
A single org has 1 or more Tenants. A Tenant is a data partition and set of 
roles that give users permission inside the tenant. Data records structurrally
live inside a tenant which structurally belongs to an org. This is represented
as columns in SQL and folders in side Object Store.

### Sandbox
A sandbox is a filtered view of Tenant. It has a list of files/features available to
it to implement access controls, data masking, etc. Sandboxes structurally
belong to tenants and can have rules that expand to/cover any of the data in the
tenant (saearch predicates).

