# Rules applied to apps that consume the :capture library.
# Room, WorkManager, CameraX and Firebase ship their own consumer rules.
# Workers are instantiated reflectively by WorkManager's default factory.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
