# BetterScheduler
BetterScheduler guarantees your tasks are run, whereas the BukkitScheduler will throw an exception if you try scheduling a task once your plugin is disabled.

BetterScheduler was created for the following scenario:
1. You schedule an async task
2. The async task schedules a sync task, but your plugin is disabled now and an IllegalPluginAccessException is thrown.

With BetterScheduler you can think of tasks that schedule other tasks as part of a 'transaction' where the tasks are guarenteed to run as a chain.
