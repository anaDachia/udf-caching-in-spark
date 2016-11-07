/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution

import java.io._
import java.nio.file.{Path, StandardOpenOption, Files}
import java.util.{ArrayList => JavaArrayList}

import org.apache.spark.SparkException
import org.apache.spark.sql.catalyst.expressions.{Projection, Row}
import org.apache.spark.sql.execution.CS143Utils._
import scala.collection.JavaConverters._

/**
  * This trait represents a regular relation that is hash partitioned and spilled to
  * disk.
  */
private[sql] sealed trait DiskHashedRelation {
  /**
    *
    * @return an iterator of the [[DiskPartition]]s that make up this relation.
    */
  def getIterator(): Iterator[DiskPartition]

  /**
    * Close all the partitions for this relation. This should involve deleting the files hashed into.
    */
  def closeAllPartitions()
}

/**
  * A general implementation of [[DiskHashedRelation]].
  *
  * @param partitions the disk partitions that we are going to spill to
  */
protected [sql] final class GeneralDiskHashedRelation(partitions: Array[DiskPartition])
  extends DiskHashedRelation with Serializable {

  override def getIterator() = {
    // IMPLEMENT ME
    partitions.iterator
  }

  override def closeAllPartitions() = {
    // IMPLEMENT ME
    partitions.foreach((_:DiskPartition).closePartition())
  }
}

private[sql] class DiskPartition (
                                   filename: String,
                                   blockSize: Int) {
  private val path: Path = Files.createTempFile("", filename)
  private val data: JavaArrayList[Row] = new JavaArrayList[Row]
  private val outStream: OutputStream = Files.newOutputStream(path)
  private val inStream: InputStream = Files.newInputStream(path)
  private val chunkSizes: JavaArrayList[Int] = new JavaArrayList[Int]()
  private var writtenToDisk: Boolean = false
  private var inputClosed: Boolean = false

  /**
    * This method inserts a new row into this particular partition. If the size of the partition
    * exceeds the blockSize, the partition is spilled to disk.
    *
    * @param row the [[Row]] we are adding
    */
  def insert(row: Row) = {
    // IMPLEMENT ME
    if (inputClosed == true) {
      throw new SparkException("cannot write in a closed partition")
    }
    // TODO if disk full
    else {
      data.add(row)
      val partition_size = measurePartitionSize()
      if (partition_size >= blockSize){
        spillPartitionToDisk()
        // TODO figure out what to do
        // data.clear()
        // closeInput()
      }
    }
  }

  /**
    * This method converts the data to a byte array and returns the size of the byte array
    * as an estimation of the size of the partition.
    *
    * @return the estimated size of the data
    */
  private[this] def measurePartitionSize(): Int = {
    CS143Utils.getBytesFromList(data).size
  }

  /**
    * Uses the [[Files]] API to write a byte array representing data to a file.
    */
  private[this] def spillPartitionToDisk() = {
    val bytes: Array[Byte] = getBytesFromList(data)

    // This array list stores the sizes of chunks written in order to read them back correctly.
    chunkSizes.add(bytes.size)

    Files.write(path, bytes, StandardOpenOption.APPEND)
    writtenToDisk = true
  }

  /**
    * If this partition has been closed, this method returns an Iterator of all the
    * data that was written to disk by this partition.
    *
    * @return the [[Iterator]] of the data
    */
  def getData(): Iterator[Row] = {
    if (!inputClosed) {
      throw new SparkException("Should not be reading from file before " +
        "closing input. Bad things will happen!")
    }

    new Iterator[Row] {
      var currentIterator: Iterator[Row] = data.iterator.asScala
      val chunkSizeIterator: Iterator[Int] = chunkSizes.iterator().asScala
      var byteArray: Array[Byte] = null

      override def next() = {
        // IMPLEMENT ME
        currentIterator.next()
      }

      override def hasNext() = {
        // IMPLEMENT ME
        var has_next = currentIterator.hasNext
        if (currentIterator.hasNext == false && chunkSizeIterator.hasNext){
          fetchNextChunk()
          has_next = true
        }
        has_next
      }

      /**
        * Fetches the next chunk of the file and updates the iterator. Should return true
        * unless the iterator is empty.
        *
        * @return true unless the iterator is empty.
        */
      private[this] def fetchNextChunk(): Boolean = {
        // IMPLEMENT ME
        if (chunkSizeIterator.hasNext){
          val size = chunkSizeIterator.next()
          if (size > 0){
            byteArray = CS143Utils.getNextChunkBytes(inStream, size,byteArray)
            currentIterator = CS143Utils.getListFromBytes(byteArray).iterator.asScala
          }
          return true
        }
        false
      }
    }
  }

  /**
    * Closes this partition, implying that no more data will be written to this partition. If getData()
    * is called without closing the partition, an error will be thrown.
    *
    * If any data has not been written to disk yet, it should be written. The output stream should
    * also be closed.
    */
  def closeInput() = {
    // IMPLEMENT ME
    inputClosed = true
    // iterate to spill to file
    if(data.isEmpty == false){ // //////////TO BE CHECKED //////////////
      spillPartitionToDisk()
      data.clear()
    }
    outStream.close()
  }


  /**
    * Closes this partition. This closes the input stream and deletes the file backing the partition.
    */
  private[sql] def closePartition() = {
    inStream.close()
    Files.deleteIfExists(path)
  }
}

private[sql] object DiskHashedRelation {

  /**
    * Given an input iterator, partitions each row into one of a number of [[DiskPartition]]s
    * and constructors a [[DiskHashedRelation]].
    *
    * This executes the first phase of external hashing -- using a course-grained hash function
    * to partition the tuples to disk.
    *
    * The block size is approximately set to 64k because that is a good estimate of the average
    * buffer page.
    *
    * @param input        the input [[Iterator]] of [[Row]]s
    * @param keyGenerator a [[Projection]] that generates the keys for the input
    * @param size         the number of [[DiskPartition]]s
    * @param blockSize    the threshold at which each partition will spill
    * @return the constructed [[DiskHashedRelation]]
    */
  def apply(
             input: Iterator[Row],
             keyGenerator: Projection,
             size: Int = 64,
             blockSize: Int = 64000) = {
    // IMPLEMENT ME
    var partition_list: Array[DiskPartition] = new Array[DiskPartition](size)
    for (a <- 0 until (size )) {
      partition_list(a) = new DiskPartition("" + a, blockSize)
    }
    for (rowi <- input) {
      var key = keyGenerator(rowi) // hash has hashcode func! partition row --> hash % size
      var ind = key.hashCode() % size // CHECK !!!
      var target_partition = partition_list(ind)
      target_partition.insert(rowi) // TODO make sure what happens in marginal cases
    }
    partition_list.foreach((_:DiskPartition).closeInput())
    new GeneralDiskHashedRelation(partition_list) // create new DiskHashedRelation
  }
}
