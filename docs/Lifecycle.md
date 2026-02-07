Uses DI
@Startup and @Shutdown @ApplicationScoped
Each service gets config from SmallRye
Servies call other service on init to force boot order
      @PostConstruct
      void init() {
				DatabaseService.ping()
			}

Services do health checks

Services resources (connections, handles, etc) should flag service down
if they detect errors/outages. Health check will poll for resolution.

ManagedService wrapper to normalize events/chaining

  public interface ManagedService {
      enum State { STOPPED, STARTING, RUNNING, STOPPING, FAILED }

      State state();
      void start() throws Exception;
      void stop() throws Exception;
      void fail( Throwable t) throws Exception;


  }

  @ApplicationScoped
  public abstract class AbstractManagedService implements ManagedService {
		// send service status change events
	}

  @DependsOn(Database.service)
  public class TaskService extends AbstractManagedService {
	 - means I cant be up if they are not up
	 - event listener/cascade
