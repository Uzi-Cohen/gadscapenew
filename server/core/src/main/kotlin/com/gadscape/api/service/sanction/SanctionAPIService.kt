package com.gadscape.api.service.sanction

import com.gadscape.api.APIClient
import com.gadscape.api.model.Sanction
import com.gadscape.api.model.SanctionLevel
import com.gadscape.api.requests.SanctionRevoke
import com.gadscape.api.requests.SanctionSubmit
import com.gadscape.api.responses.SanctionRevokeResponse
import com.gadscape.api.responses.SanctionSubmitResponse
import com.gadscape.api.service.APIService
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.post
import io.ktor.server.routing.*
import com.gadscape.api.resources.Sanction as SanctionResource

object SanctionAPIService : APIService() {

    fun submit(sanction: Sanction, onResponse: (SanctionSubmitResponse) -> Unit) {
        if (!APIClient.post<SanctionSubmit, SanctionSubmitResponse, SanctionResource.Submit>(
            resource = SanctionResource.Submit(),
            request = SanctionSubmit(sanction),
            onSuccess = {
                onResponse(this)
            },
            onFailed = {
                onResponse(SanctionSubmitResponse.Error(it?.message ?: "An error occurred"))
            }
        )) {
            onResponse(SanctionSubmitResponse.Error("The API is offline, failed to submit sanction"))
        }
    }

    fun revoke(sanctionId: Long, level: SanctionLevel, onResponse: (SanctionRevokeResponse) -> Unit) {
        if (!APIClient.post<SanctionRevokeResponse, SanctionResource.Revoke>(
            resource = SanctionResource.Revoke(id = sanctionId, level = level),
            onSuccess = {
                onResponse(this)
            },
            onFailed = {
                onResponse(SanctionRevokeResponse.Error(it?.message ?: "An error occurred"))
            }
        )) {
            onResponse(SanctionRevokeResponse.Error("The API is offline, failed to revoke sanction"))
        }
    }

    fun Routing.sanctionCallback() {
        post<SanctionResource.Callback.Submit> {
            val request = call.receive<SanctionSubmit>()
            SanctionPlayerHandler.onSanctionReceived(request.sanction)
        }
        post<SanctionResource.Callback.Revoke> {
            val request = call.receive<SanctionRevoke>()
            SanctionPlayerHandler.onSanctionRevoked(request.sanction)
        }
    }
}
