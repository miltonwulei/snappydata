CLOSETASK   taskClass   = io.snappydata.hydra.cluster.SnappyTest taskMethod  = HydraTask_stopSnappyCluster
            runMode = always
            threadGroups = snappyThreads;

CLOSETASK   taskClass   = io.snappydata.hydra.cluster.SnappyTest taskMethod  = HydraTask_startSparkCluster
            runMode = always
            threadGroups = snappyThreads;