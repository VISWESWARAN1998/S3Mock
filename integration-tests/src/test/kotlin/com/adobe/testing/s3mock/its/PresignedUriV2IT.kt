/*
 *  Copyright 2017-2024 Adobe.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.adobe.testing.s3mock.its

import com.adobe.testing.s3mock.util.DigestUtil
import org.apache.http.HttpHost
import org.apache.http.HttpStatus
import org.apache.http.client.methods.HttpDelete
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPost
import org.apache.http.client.methods.HttpPut
import org.apache.http.entity.FileEntity
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClients
import org.apache.http.message.BasicHeader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload
import software.amazon.awssdk.services.s3.model.CompletedPart
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.UploadPartRequest
import software.amazon.awssdk.services.s3.presigner.model.AbortMultipartUploadPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.CompleteMultipartUploadPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.CreateMultipartUploadPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

internal class PresignedUriV2IT : S3TestBase() {
  private lateinit var httpClient: CloseableHttpClient

  @BeforeEach
  fun setupHttpClient() {
    httpClient = HttpClients.createDefault()
  }

  @AfterEach
  fun shutdownHttpClient() {
    httpClient.close()
  }

  @Test
  fun testPresignedUri_getObject(testInfo: TestInfo) {
    val key = UPLOAD_FILE_NAME
    val (bucketName, _) = givenBucketAndObjectV2(testInfo, key)

    val getObjectRequest = GetObjectRequest
      .builder()
      .bucket(bucketName)
      .key(key)
      .build()

    val presignedGetObjectRequest = s3Presigner.presignGetObject(
      GetObjectPresignRequest
        .builder()
        .getObjectRequest(getObjectRequest)
        .signatureDuration(Duration.ofMinutes(1L))
        .build()
    )

    val presignedUrlString = presignedGetObjectRequest.url().toString()
    assertThat(presignedUrlString).isNotBlank()

    val getObject = HttpGet(presignedUrlString)
    httpClient.execute(
      HttpHost(
        host, httpPort
      ), getObject
    ).use {
      assertThat(it.statusLine.statusCode).isEqualTo(HttpStatus.SC_OK)
      val expectedEtag = "\"${DigestUtil.hexDigest(Files.newInputStream(Path.of(UPLOAD_FILE_NAME)))}\""
      val actualEtag = "\"${DigestUtil.hexDigest(it.entity.content)}\""
      assertThat(actualEtag).isEqualTo(expectedEtag)
    }
  }

  @Test
  fun testPresignedUri_putObject(testInfo: TestInfo) {
    val key = UPLOAD_FILE_NAME
    val bucketName = givenBucketV2(testInfo)

    val putObjectRequest = PutObjectRequest
      .builder()
      .bucket(bucketName)
      .key(key)
      .build()

    val presignedPutObjectRequest = s3Presigner.presignPutObject(
      PutObjectPresignRequest
        .builder()
        .putObjectRequest(putObjectRequest)
        .signatureDuration(Duration.ofMinutes(1L))
        .build()
    )

    val presignedUrlString = presignedPutObjectRequest.url().toString()
    assertThat(presignedUrlString).isNotBlank()

    val putObject = HttpPut(presignedUrlString).apply {
      this.entity = FileEntity(File(UPLOAD_FILE_NAME))
    }

    httpClient.execute(
      HttpHost(
        host, httpPort
      ), putObject
    ).use {
      assertThat(it.statusLine.statusCode).isEqualTo(HttpStatus.SC_OK)
    }

    s3ClientV2.getObject(GetObjectRequest
      .builder()
      .bucket(bucketName)
      .key(key)
      .build()
    ).use {
      val expectedEtag = "\"${DigestUtil.hexDigest(Files.newInputStream(Path.of(UPLOAD_FILE_NAME)))}\""
      val actualEtag = "\"${DigestUtil.hexDigest(it)}\""
      assertThat(actualEtag).isEqualTo(expectedEtag)
    }
  }

  @Test
  fun testPresignedUri_createMultipartUpload(testInfo: TestInfo) {
    val key = UPLOAD_FILE_NAME
    val bucketName = givenBucketV2(testInfo)

    val createMultipartUploadRequest = CreateMultipartUploadRequest
      .builder()
      .bucket(bucketName)
      .key(key)
      .build()

    val presignCreateMultipartUpload = s3Presigner.presignCreateMultipartUpload(
      CreateMultipartUploadPresignRequest
        .builder()
        .createMultipartUploadRequest(createMultipartUploadRequest)
        .signatureDuration(Duration.ofMinutes(1L))
        .build()
    )

    val presignedUrlString = presignCreateMultipartUpload.url().toString()
    assertThat(presignedUrlString).isNotBlank()

    val postObject = HttpPost(presignedUrlString).apply {
      this.entity = StringEntity(
        """<?xml version="1.0" encoding="UTF-8"?>
    <InitiateMultipartUploadResult>
      <Bucket>bucketName</Bucket>
      <Key>fileName</Key>
      <UploadId>uploadId</UploadId>
    </InitiateMultipartUploadResult>
    """)
    }
    httpClient.execute(
      HttpHost(
        host, httpPort
      ), postObject
    ).use {
      assertThat(it.statusLine.statusCode).isEqualTo(HttpStatus.SC_OK)
    }

    val listMultipartUploads = s3ClientV2.listMultipartUploads(
      ListMultipartUploadsRequest
        .builder()
        .bucket(bucketName)
        .keyMarker(key)
        .build()
    )

    assertThat(listMultipartUploads.uploads()).hasSize(1)
  }

  @Test
  fun testPresignedUri_abortMultipartUpload(testInfo: TestInfo) {
    val key = UPLOAD_FILE_NAME
    val bucketName = givenBucketV2(testInfo)
    val file = File(UPLOAD_FILE_NAME)

    val createMultipartUpload = s3ClientV2.createMultipartUpload(
      CreateMultipartUploadRequest
        .builder()
        .bucket(bucketName)
        .key(key)
        .build()
    )

    val uploadId = createMultipartUpload.uploadId()
    s3ClientV2.uploadPart(
      UploadPartRequest
        .builder()
        .bucket(createMultipartUpload.bucket())
        .key(createMultipartUpload.key())
        .uploadId(uploadId)
        .partNumber(1)
        .contentLength(file.length()).build(),
      RequestBody.fromFile(file),
    )

    val abortMultipartUploadRequest = AbortMultipartUploadRequest
      .builder()
      .bucket(bucketName)
      .key(key)
      .uploadId(uploadId)
      .build()

    val presignAbortMultipartUpload = s3Presigner.presignAbortMultipartUpload(
      AbortMultipartUploadPresignRequest
        .builder()
        .abortMultipartUploadRequest(abortMultipartUploadRequest)
        .signatureDuration(Duration.ofMinutes(1L))
        .build()
    )

    val presignedUrlString = presignAbortMultipartUpload.url().toString()
    assertThat(presignedUrlString).isNotBlank()

    val httpDelete = HttpDelete(presignedUrlString)
    httpClient.execute(
      HttpHost(
        host, httpPort
      ), httpDelete
    ).use {
      assertThat(it.statusLine.statusCode).isEqualTo(HttpStatus.SC_NO_CONTENT)
    }

    val listMultipartUploads = s3ClientV2.listMultipartUploads(
      ListMultipartUploadsRequest
        .builder()
        .bucket(bucketName)
        .keyMarker(key)
        .build()
    )

    assertThat(listMultipartUploads.uploads()).isEmpty()
  }

  @Test
  fun testPresignedUri_completeMultipartUpload(testInfo: TestInfo) {
    val key = UPLOAD_FILE_NAME
    val bucketName = givenBucketV2(testInfo)
    val file = File(UPLOAD_FILE_NAME)

    val createMultipartUpload = s3ClientV2.createMultipartUpload(
      CreateMultipartUploadRequest
        .builder()
        .bucket(bucketName)
        .key(key)
        .build()
    )

    val uploadId = createMultipartUpload.uploadId()
    val uploadPartResult = s3ClientV2.uploadPart(
      UploadPartRequest
        .builder()
        .bucket(createMultipartUpload.bucket())
        .key(createMultipartUpload.key())
        .uploadId(uploadId)
        .partNumber(1)
        .contentLength(file.length()).build(),
      RequestBody.fromFile(file),
    )

    val completeMultipartUploadRequest = CompleteMultipartUploadRequest
      .builder()
      .bucket(bucketName)
      .key(key)
      .uploadId(uploadId)
      .build()

    val presignCompleteMultipartUpload = s3Presigner.presignCompleteMultipartUpload(
      CompleteMultipartUploadPresignRequest
        .builder()
        .completeMultipartUploadRequest(completeMultipartUploadRequest)
        .signatureDuration(Duration.ofMinutes(1L))
        .build()
    )

    val presignedUrlString = presignCompleteMultipartUpload.url().toString()
    assertThat(presignedUrlString).isNotBlank()

    val httpPost = HttpPost(presignedUrlString).apply {
      this.setHeader(BasicHeader("Content-Type", "application/xml"))
      this.entity = StringEntity(
        """<CompleteMultipartUpload>
        <Part>
          <ETag>${uploadPartResult.eTag()}</ETag>
          <PartNumber>1</PartNumber>
        </Part>
      </CompleteMultipartUpload>
      """)
    }
    httpClient.execute(
      HttpHost(
        host, httpPort
      ), httpPost
    ).use {
      assertThat(it.statusLine.statusCode).isEqualTo(HttpStatus.SC_OK)
    }

    val listMultipartUploads = s3ClientV2.listMultipartUploads(
      ListMultipartUploadsRequest
        .builder()
        .bucket(bucketName)
        .keyMarker(key)
        .build()
    )

    assertThat(listMultipartUploads.uploads()).isEmpty()
  }


  @Test
  fun testPresignedUri_uploadPart(testInfo: TestInfo) {
    val key = UPLOAD_FILE_NAME
    val bucketName = givenBucketV2(testInfo)
    val file = File(UPLOAD_FILE_NAME)

    val createMultipartUpload = s3ClientV2.createMultipartUpload(
      CreateMultipartUploadRequest
        .builder()
        .bucket(bucketName)
        .key(key)
        .build()
    )

    val uploadId = createMultipartUpload.uploadId()
    val uploadPartRequest = UploadPartRequest
      .builder()
      .bucket(createMultipartUpload.bucket())
      .key(createMultipartUpload.key())
      .uploadId(uploadId)
      .partNumber(1)
      .contentLength(file.length()).build()

    val presignUploadPart = s3Presigner.presignUploadPart(
      UploadPartPresignRequest
        .builder()
        .uploadPartRequest(uploadPartRequest)
        .signatureDuration(Duration.ofMinutes(1L))
        .build()
    )

    val presignedUrlString = presignUploadPart.url().toString()
    assertThat(presignedUrlString).isNotBlank()

    val httpPut = HttpPut(presignedUrlString).apply {
      this.entity = FileEntity(File(UPLOAD_FILE_NAME))
    }
    httpClient.execute(
      HttpHost(
        host, httpPort
      ), httpPut
    ).use {
      assertThat(it.statusLine.statusCode).isEqualTo(HttpStatus.SC_OK)
      val completeMultipartUploadRequest = CompleteMultipartUploadRequest
        .builder()
        .bucket(bucketName)
        .key(key)
        .uploadId(uploadId)
        .multipartUpload(
          CompletedMultipartUpload
            .builder()
            .parts(
              CompletedPart
                .builder()
                .eTag(it.getFirstHeader("ETag").value)
                .partNumber(1)
                .build()
            )
            .build()
        )
        .build()

      s3ClientV2.completeMultipartUpload(completeMultipartUploadRequest)
    }

    val listMultipartUploads = s3ClientV2.listMultipartUploads(
      ListMultipartUploadsRequest
        .builder()
        .bucket(bucketName)
        .keyMarker(key)
        .build()
    )

    assertThat(listMultipartUploads.uploads()).isEmpty()
  }

}
