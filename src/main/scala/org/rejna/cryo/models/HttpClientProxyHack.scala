package org.rejna.cryo.models

import org.apache.http.client.HttpClient
import org.apache.http.client.params.AuthPolicy
import org.apache.http.auth.params.AuthPNames

import com.amazonaws.AmazonWebServiceClient
import com.amazonaws.http.AmazonHttpClient

object HttpClientProxyHack {
  def apply(awsClient: AmazonWebServiceClient): Unit = {
    val clientField = classOf[AmazonWebServiceClient].getDeclaredField("client")
    clientField.setAccessible(true)
    val client = clientField.get(awsClient).asInstanceOf[AmazonHttpClient]
    val httpClientField = classOf[AmazonHttpClient].getDeclaredField("httpClient")
    httpClientField.setAccessible(true)
    val httpClient = httpClientField.get(client).asInstanceOf[HttpClient]
    val l = new java.util.ArrayList[String]
    l.add(AuthPolicy.BASIC)
    httpClient.getParams.setParameter(AuthPNames.PROXY_AUTH_PREF, l)
  }
}