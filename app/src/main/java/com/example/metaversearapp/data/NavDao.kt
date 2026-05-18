package com.example.metaversearapp.data

import androidx.room.*

@Dao
interface NavDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNode(node: NavNode)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEdge(edge: NavEdge)

    @Update
    suspend fun updateNode(node: NavNode)

    @Query("SELECT * FROM nav_nodes")
    suspend fun getAllNodes(): List<NavNode>

    @Query("SELECT * FROM nav_edges")
    suspend fun getAllEdges(): List<NavEdge>

    @Query("SELECT * FROM nav_nodes WHERE id = :id")
    suspend fun getNodeById(id: String): NavNode?

    @Query("DELETE FROM nav_nodes")
    suspend fun clearNodes()

    @Query("DELETE FROM nav_edges")
    suspend fun clearEdges()

    @Query("SELECT COUNT(*) FROM nav_nodes")
    suspend fun nodeCount(): Int

    @Query("SELECT COUNT(*) FROM nav_edges")
    suspend fun edgeCount(): Int

    /** Returns all nodes whose [NodeType] matches [type] (stored as its name string). */
    @Query("SELECT * FROM nav_nodes WHERE type = :type")
    suspend fun getNodesByType(type: String): List<NavNode>

    /**
     * Removes every edge whose [NavEdge.fromId] or [NavEdge.toId] no longer exists
     * in [nav_nodes].  Call this after any bulk node deletion (Gist sync, manual
     * clear) to keep the edge table consistent and prevent the pathfinder from
     * traversing stale references.
     */
    @Query("""
        DELETE FROM nav_edges
        WHERE fromId NOT IN (SELECT id FROM nav_nodes)
           OR toId   NOT IN (SELECT id FROM nav_nodes)
    """)
    suspend fun pruneOrphanEdges()

    /** Deletes a single node and all edges that reference it. */
    @Query("DELETE FROM nav_nodes WHERE id = :nodeId")
    suspend fun deleteNode(nodeId: String)

    /** Returns all edges that touch [nodeId] (either as from or to endpoint). */
    @Query("SELECT * FROM nav_edges WHERE fromId = :nodeId OR toId = :nodeId")
    suspend fun getEdgesForNode(nodeId: String): List<NavEdge>

    /** Removes all edges connected to [nodeId] (call before or after deleteNode). */
    @Query("DELETE FROM nav_edges WHERE fromId = :nodeId OR toId = :nodeId")
    suspend fun deleteEdgesForNode(nodeId: String)
}
