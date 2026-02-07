Uses Liquibase for schema
Uses JDBI Delcarative / SQLObject API
Uses AgroalDataSource/built-in for pooling

```
public interface UserDao {
    @SqlUpdate("CREATE TABLE \"user\" (id INTEGER PRIMARY KEY, \"name\" VARCHAR)")
    void createTable();

    @SqlUpdate("INSERT INTO \"user\" (id, \"name\") VALUES (?, ?)")
    void insertPositional(int id, String name);

    @SqlUpdate("INSERT INTO \"user\" (id, \"name\") VALUES (:id, :name)")
    void insertNamed(@Bind("id") int id, @Bind("name") String name);

    @SqlUpdate("INSERT INTO \"user\" (id, \"name\") VALUES (:id, :name)")
    void insertBean(@BindBean User user);

    @SqlQuery("SELECT * FROM \"user\" ORDER BY \"name\"")
    @RegisterBeanMapper(User.class)
    List<User> listUsers();
}
```

Migration on start:
```
  @ApplicationScoped
  @Startup
  public class DatabaseMigrator {

      @Inject Liquibase liquibase;

      @PostConstruct
      void migrate() {
          // Blocking is OK - migrations are usually fast
          // and MUST complete before app starts
          liquibase.migrate();
          Log.info("Database migrations applied");
      }
  }
```
