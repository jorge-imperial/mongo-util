package com.mongodb.cloud;


import com.mongodb.atlas.model.DatabasesResult;
import com.mongodb.atlas.model.MeasurementsResult;
import com.mongodb.atlas.model.ProcessesResult;
import com.mongodb.cloud.model.Cluster;
import com.mongodb.cloud.model.ClustersResult;
import com.mongodb.cloud.model.DisksResult;
import com.mongodb.cloud.model.HostsResult;

import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Path;

public interface AtlasApi {
    
    @GET("groups/{groupId}/clusters")
    Call<ClustersResult> getClusters(@Path("groupId") String groupId);
    
    @GET("groups/{groupId}/hosts")
    Call<HostsResult> getHosts(@Path("groupId") String groupId);
    
    @GET("groups/{groupId}/hosts/{hostId}/disks")
    Call<DisksResult> getDisks(@Path("groupId") String groupId, @Path("hostId") String hostId);
    
    @GET("groups/{groupId}/clusters/{clusterName}")
    Call<Cluster> getCluster(@Path("groupId") String groupId, @Path("clusterName") String clusterName);
    
    @GET("groups/{groupId}/processes")
    Call<ProcessesResult> getProcesses(@Path("groupId") String groupId);
    
    @GET("groups/{groupId}/processes/{hostId}/measurements?granularity=PT1M&period=P2D&&m=CACHE_BYTES_READ_INTO&m=CACHE_BYTES_WRITTEN_FROM")
    Call<MeasurementsResult> getMeasurements(@Path("groupId") String groupId, @Path("hostId") String hostId);
    
    @GET("groups/{groupId}/hosts/{hostId}/disks/{partitionName}/measurements?granularity=PT1H&period=P2D")
    Call<MeasurementsResult> getDiskMeasurements(@Path("groupId") String groupId, @Path("hostId") String hostId, @Path("partitionName") String partitionName);
    
    @GET("groups/{groupId}/processes/{hostId}/disks/xbdb/measurements?granularity=PT1M&period=P2D&m=DISK_PARTITION_IOPS_TOTAL")
    Call<MeasurementsResult> getDiskMeasurements(@Path("groupId") String groupId, @Path("hostId") String hostId);
    
    
    //GET api/atlas/v1.0/groups/{GROUP-ID}/processes/{HOST}:{PORT}/databases
    @GET("groups/{groupId}/processes/{hostId}/databases")
    Call<DatabasesResult> getDatabases(@Path("groupId") String groupId, @Path("hostId") String hostId);
    
    //GET api/atlas/v1.0/groups/{GROUP-ID}/processes/{HOST}:{PORT}/databases/{DATABASE-ID}/measurements
    @GET("groups/{groupId}/processes/{hostId}/databases/{databaseName}/measurements?granularity=PT24H&period=PT24H")
    Call<MeasurementsResult> getDatabaseMeasurements(@Path("groupId") String groupId,
            @Path("hostId") String hostId, @Path("databaseName") String databaseName);

}
