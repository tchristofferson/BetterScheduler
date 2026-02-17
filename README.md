# BetterScheduler
BetterScheduler guarantees your tasks are run, whereas the BukkitScheduler will throw an exception if you try scheduling a task once your plugin is disabled.

BetterScheduler was created for the following scenario:
1. You schedule an async task
2. The async task schedules a sync task, but your plugin is disabled now and an IllegalPluginAccessException is thrown.

With BetterScheduler you can think of tasks that schedule other tasks as part of a 'transaction' where the tasks are guarenteed to run as a chain.

### Setup
```
public class MyPlugin extends JavaPlugin {
    private TaskQueueRunner taskQueueRunner;
    
    @Override
    public void onEnable() {
        taskQueueRunner = new TaskQueueRunner(this);
    }
}
```

### Proper Shutdown
It is imperative in the onDisable method of your plugin you shut down the TaskQueueRunner.
If you don't do this, there is a possibility some sync tasks never run and async tasks hang without completing.
This will wait for all tasks to be completed and WILL block the main thread if there are tasks in progress.
Any plugins using this library should make it known that the onDisable should never run unless the server is shut down.
```
@Override
public void onDisable() {
    taskQueueRunner.shutdown();
}
```

### Samples
Schedule an async task that schedules and waits for a sync task
```
taskQueueRunner.scheduleAsyncTask(new BSAsyncTask(plugin) {
    @Override
    public void run() throws Exception {
        Future<Boolean> future = taskQueueRunner.submitSyncTask(new BSCallable<>() {
            @Override
            protected Boolean execute() throws Exception {
                return true;//Sample, just return true
            }
        });
        
        //Wait for the sync task to complete and get the returned value
        boolean success = future.get();
        ...
    }
});
```

### Misc
If an async task is scheduled from a Thread that Bukkit.isPrimaryThread() returns false for, the task will run on the current thread.
If a sync task is scheduled on a Thread that Bukkit.isPrimaryThread() returns true for, the task will run on the current thread (main thread).

### Maven
IMPORTANT:
1. Create a GitHub classic token [here](https://github.com/settings/tokens) and check `read:packages`
2. Create maven settings.xml file in ~/.m2, if you don't already, and add the credentials to it. It should look similar to this:
```
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>{GITHUB_USERNAME}</username>
      <password>{TOKEN}</password>
    </server>
  </servers>
</settings>
```
pom.xml:
```
<repository>
    <id>better-scheduler-github</id>
    <url>https://maven.pkg.github.com/tchristofferson/BetterScheduler</url>
    <snapshots>
        <enabled>true</enabled>
    </snapshots>
</repository>

<dependency>
  <groupId>com.tchristofferson</groupId>
  <artifactId>better-scheduler</artifactId>
  <version>1.0-SNAPSHOT</version>
</dependency>
```