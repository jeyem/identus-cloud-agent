/**
 *
 * Please note:
 * This class is auto generated by OpenAPI Generator (https://openapi-generator.tech).
 * Do not edit this file manually.
 *
 */

@file:Suppress(
    "ArrayInDataClass",
    "EnumEntryName",
    "RemoveRedundantQualifierName",
    "UnusedImport"
)

package org.hyperledger.identus.client.models


import com.google.gson.annotations.SerializedName

/**
 * 
 *
 * @param did 
 * @param parameterType 
 */


data class DidParameter (

    @SerializedName("did")
    override val did: kotlin.String,

    @SerializedName("parameterType")
    override val parameterType: kotlin.String = "DidParameter",

    @get:SerializedName("dateTime")
    override val dateTime: java.time.OffsetDateTime? = null,
) : VcVerificationParameter

