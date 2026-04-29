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
}
