#!/usr/bin/env bash
# Configura o tenant externo do Entra External ID usado pelo Duora (docs/adr/0001):
#   - duora-api:     a API, com o scope access_as_user, o app role ADMIN e tokens v2
#   - duora-android: app Android (Kotlin), cliente público com MSAL
#   - duora-desktop: app desktop (Java), cliente público com MSAL4J e retorno em http://localhost
#   - consentimento de administrador para os dois clientes
#   - fluxo de cadastro e login com e-mail e senha, ligado aos dois clientes
#
# Pode ser executado de novo: o que já existe é reaproveitado e atualizado, nada é duplicado.
# Pré-requisito: az login --tenant "$TENANT_ID" --allow-no-subscriptions
set -euo pipefail

TENANT_ID="${TENANT_ID:-d3e03557-0f3b-4474-9586-7ad43938423d}"
TENANT_SUBDOMAIN="${TENANT_SUBDOMAIN:-duoraapp}"
ANDROID_PACKAGE="${ANDROID_PACKAGE:-duoraapp.tech}"
# Base64 do SHA-1 do certificado que assina o APK. Padrão: keystore de debug desta máquina.
# A chave de release terá outro hash: rode de novo com ANDROID_SIGNATURE_HASHES="debug release".
ANDROID_SIGNATURE_HASHES="${ANDROID_SIGNATURE_HASHES:-$(keytool -exportcert -alias androiddebugkey \
    -keystore "$HOME/.android/debug.keystore" -storepass android 2>/dev/null | openssl sha1 -binary | openssl base64)}"

readonly GRAPH="https://graph.microsoft.com/v1.0"
readonly MICROSOFT_GRAPH_APP_ID="00000003-0000-0000-c000-000000000000"
readonly GRAPH_SCOPE_OPENID="37f7f235-527c-4136-accd-4a02d197296e"
readonly GRAPH_SCOPE_OFFLINE_ACCESS="7427e0e9-2fba-42fe-b0c0-848c9e6a8182"
# Ids fixos: atualizar um scope ou role com o mesmo id é idempotente; trocar o id criaria outro.
readonly API_SCOPE_ID="fca6283e-8ed9-4339-ab91-844d28e58765"
readonly ADMIN_ROLE_ID="8befb439-758d-4847-b204-d8d137545eb4"
readonly USER_FLOW_NAME="duora-signup-signin"

graph() {
    local method="$1" path="$2" body="${3:-}"
    if [[ -n "$body" ]]; then
        az rest --method "$method" --url "$GRAPH$path" --headers Content-Type=application/json --body "$body"
    else
        az rest --method "$method" --url "$GRAPH$path"
    fi
}

main() {
    require_tenant_session

    local api_app_id android_app_id desktop_app_id
    api_app_id="$(ensure_app duora-api)"
    configure_api "$api_app_id"

    android_app_id="$(ensure_app duora-android)"
    configure_public_client "$android_app_id" "$api_app_id" "$(android_redirect_uris)"

    desktop_app_id="$(ensure_app duora-desktop)"
    configure_public_client "$desktop_app_id" "$api_app_id" '["http://localhost"]'

    local api_sp
    api_sp="$(ensure_service_principal "$api_app_id")"
    grant_admin_consent "$android_app_id" "$api_sp"
    grant_admin_consent "$desktop_app_id" "$api_sp"

    ensure_user_flow "$android_app_id" "$desktop_app_id"

    print_settings "$api_app_id" "$android_app_id" "$desktop_app_id"
}

require_tenant_session() {
    local current
    current="$(az account show --query tenantId -o tsv)"
    if [[ "$current" != "$TENANT_ID" ]]; then
        echo "Sessão do az no tenant $current, esperado $TENANT_ID." >&2
        echo "Rode: az login --tenant $TENANT_ID --allow-no-subscriptions" >&2
        exit 1
    fi
}

# Imprime o appId do registro com esse nome, criando-o se não existir.
ensure_app() {
    local name="$1" app_id
    app_id="$(az ad app list --filter "displayName eq '$name'" --query "[0].appId" -o tsv)"
    if [[ -z "$app_id" ]]; then
        app_id="$(az ad app create --display-name "$name" --sign-in-audience AzureADMyOrg --query appId -o tsv)"
        echo "Criado $name ($app_id)" >&2
    fi
    ensure_service_principal "$app_id" >/dev/null
    echo "$app_id"
}

# Imprime o object id do service principal do app, criando-o se não existir.
ensure_service_principal() {
    local app_id="$1" sp_id
    sp_id="$(az ad sp list --filter "appId eq '$app_id'" --query "[0].id" -o tsv)"
    if [[ -z "$sp_id" ]]; then
        sp_id="$(az ad sp create --id "$app_id" --query id -o tsv)"
    fi
    echo "$sp_id"
}

configure_api() {
    local app_id="$1"
    graph PATCH "/applications(appId='$app_id')" "$(jq -n \
        --arg uri "api://$app_id" --arg scope "$API_SCOPE_ID" --arg role "$ADMIN_ROLE_ID" '{
        identifierUris: [$uri],
        api: {
            requestedAccessTokenVersion: 2,
            oauth2PermissionScopes: [{
                id: $scope, value: "access_as_user", type: "User", isEnabled: true,
                adminConsentDisplayName: "Acessar a API do Duora",
                adminConsentDescription: "Permite que o app chame a API do Duora em nome do usuário.",
                userConsentDisplayName: "Acessar o Duora",
                userConsentDescription: "Permite que o app acesse o Duora em seu nome."
            }]
        },
        appRoles: [{
            id: $role, value: "ADMIN", displayName: "Administrador", isEnabled: true,
            description: "Acesso às rotas administrativas da API do Duora.",
            allowedMemberTypes: ["User"]
        }]
    }')" >/dev/null
    echo "duora-api configurada" >&2
}

configure_public_client() {
    local app_id="$1" api_app_id="$2" redirect_uris="$3"
    graph PATCH "/applications(appId='$app_id')" "$(jq -n \
        --argjson redirects "$redirect_uris" --arg api "$api_app_id" --arg scope "$API_SCOPE_ID" \
        --arg graph "$MICROSOFT_GRAPH_APP_ID" --arg openid "$GRAPH_SCOPE_OPENID" \
        --arg offline "$GRAPH_SCOPE_OFFLINE_ACCESS" '{
        isFallbackPublicClient: true,
        publicClient: { redirectUris: $redirects },
        requiredResourceAccess: [
            { resourceAppId: $api, resourceAccess: [{ id: $scope, type: "Scope" }] },
            { resourceAppId: $graph, resourceAccess: [
                { id: $openid, type: "Scope" }, { id: $offline, type: "Scope" } ] }
        ]
    }')" >/dev/null
    echo "cliente $app_id configurado" >&2
}

android_redirect_uris() {
    local hash uris=()
    for hash in $ANDROID_SIGNATURE_HASHES; do
        uris+=("msauth://$ANDROID_PACKAGE/$(jq -rn --arg h "$hash" '$h | @uri')")
    done
    printf '%s\n' "${uris[@]}" | jq -R . | jq -s .
}

# Consentimento de administrador (todos os usuários) para o scope da API e para openid/offline_access.
grant_admin_consent() {
    local client_app_id="$1" api_sp="$2" client_sp graph_sp
    client_sp="$(ensure_service_principal "$client_app_id")"
    graph_sp="$(ensure_service_principal "$MICROSOFT_GRAPH_APP_ID")"
    ensure_grant "$client_sp" "$api_sp" "access_as_user"
    ensure_grant "$client_sp" "$graph_sp" "openid offline_access"
}

ensure_grant() {
    local client_sp="$1" resource_sp="$2" scope="$3" grant_id
    grant_id="$(graph GET "/oauth2PermissionGrants?\$filter=clientId eq '$client_sp' and resourceId eq '$resource_sp'" \
        | jq -r '.value[0].id // empty')"
    local body
    if [[ -z "$grant_id" ]]; then
        body="$(jq -n --arg c "$client_sp" --arg r "$resource_sp" --arg s "$scope" \
            '{clientId: $c, consentType: "AllPrincipals", resourceId: $r, scope: $s}')"
        graph POST "/oauth2PermissionGrants" "$body" >/dev/null
    else
        graph PATCH "/oauth2PermissionGrants/$grant_id" "$(jq -n --arg s "$scope" '{scope: $s}')" >/dev/null
    fi
}

ensure_user_flow() {
    local flow_id app_id
    flow_id="$(graph GET "/identity/authenticationEventsFlows" \
        | jq -r --arg n "$USER_FLOW_NAME" '.value[] | select(.displayName == $n) | .id' | head -n 1)"
    if [[ -z "$flow_id" ]]; then
        flow_id="$(graph POST "/identity/authenticationEventsFlows" "$(user_flow_body)" | jq -r .id)"
        echo "Fluxo $USER_FLOW_NAME criado" >&2
    fi
    local linked
    linked="$(graph GET "/identity/authenticationEventsFlows/$flow_id/conditions/applications/includeApplications" \
        | jq -r '.value[].appId')"
    for app_id in "$@"; do
        if ! grep -qx "$app_id" <<<"$linked"; then
            graph POST "/identity/authenticationEventsFlows/$flow_id/conditions/applications/includeApplications" \
                "$(jq -n --arg a "$app_id" '{"@odata.type": "#microsoft.graph.authenticationConditionApplication", appId: $a}')" \
                >/dev/null
        fi
    done
}

# Coleta só o e-mail: nome de exibição e demais dados de perfil pertencem ao Duora, não ao provedor.
user_flow_body() {
    jq -n --arg n "$USER_FLOW_NAME" '{
        "@odata.type": "#microsoft.graph.externalUsersSelfServiceSignUpEventsFlow",
        displayName: $n,
        onAuthenticationMethodLoadStart: {
            "@odata.type": "#microsoft.graph.onAuthenticationMethodLoadStartExternalUsersSelfServiceSignUp",
            identityProviders: [{ id: "EmailPassword-OAUTH" }]
        },
        onInteractiveAuthFlowStart: {
            "@odata.type": "#microsoft.graph.onInteractiveAuthFlowStartExternalUsersSelfServiceSignUp",
            isSignUpAllowed: true
        },
        onAttributeCollection: {
            "@odata.type": "#microsoft.graph.onAttributeCollectionExternalUsersSelfServiceSignUp",
            attributes: [{
                id: "email", displayName: "Email Address", description: "Email address of the user",
                userFlowAttributeType: "builtIn", dataType: "string"
            }],
            attributeCollectionPage: { views: [{ inputs: [{
                attribute: "email", label: "E-mail", inputType: "text", hidden: true,
                editable: false, writeToDirectory: true, required: true
            }] }] }
        }
    }'
}

print_settings() {
    local api_app_id="$1" android_app_id="$2" desktop_app_id="$3"
    cat <<EOF

API (variáveis de ambiente):
  DUORA_AUTH_ISSUER_URI=https://$TENANT_ID.ciamlogin.com/$TENANT_ID/v2.0
  DUORA_AUTH_JWK_SET_URI=https://$TENANT_SUBDOMAIN.ciamlogin.com/$TENANT_ID/discovery/v2.0/keys
  DUORA_AUTH_AUDIENCE=$api_app_id

Clientes:
  authority      https://$TENANT_SUBDOMAIN.ciamlogin.com/$TENANT_ID
  scope          api://$api_app_id/access_as_user
  duora-android  client id $android_app_id, redirect $(android_redirect_uris | jq -r '.[0]')
  duora-desktop  client id $desktop_app_id, redirect http://localhost
EOF
}

main "$@"
