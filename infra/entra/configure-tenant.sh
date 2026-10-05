#!/usr/bin/env bash
# Configura o tenant externo do Entra External ID usado pelo Duora (docs/adr/0001):
#   - duora-api:     a API, com o scope access_as_user, o app role ADMIN e tokens v2
#   - duora-web:     o login do front web feito pelo Spring (BFF, docs/adr/0002), cliente confidencial
#   - duora-android: app Android (Kotlin, ainda não decidido), cliente público com MSAL
#   - consentimento de administrador para os clientes
#   - fluxo de cadastro e login com e-mail e senha, ligado aos clientes
#   - remove registros que deixaram de existir (duora-desktop)
#
# O segredo do duora-web vai para $DEV_ENV (permissão 600), nunca para o terminal ou o repositório.
# Ele só é criado quando o arquivo ainda não tem um; para trocar, apague a linha e rode de novo.
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

DEV_ENV="${DEV_ENV:-$HOME/.config/duora/dev.env}"
# Endereços do front em desenvolvimento: Vite (5173, com proxy para a API) e a API direto (8080).
WEB_ORIGINS="${WEB_ORIGINS:-http://localhost:5173 http://localhost:8080}"
readonly WEB_SECRET_LIFETIME_DAYS=180
readonly OBSOLETE_APPS=(duora-desktop)

readonly GRAPH="https://graph.microsoft.com/v1.0"
readonly MICROSOFT_GRAPH_APP_ID="00000003-0000-0000-c000-000000000000"
readonly GRAPH_SCOPE_OPENID="37f7f235-527c-4136-accd-4a02d197296e"
readonly GRAPH_SCOPE_OFFLINE_ACCESS="7427e0e9-2fba-42fe-b0c0-848c9e6a8182"
readonly GRAPH_SCOPE_PROFILE="14dad69e-099b-42c9-810b-d002981feec1"
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

    local flow_id
    flow_id="$(ensure_user_flow)"
    remove_obsolete_apps "$flow_id"

    local api_app_id web_app_id android_app_id
    api_app_id="$(ensure_app duora-api)"
    configure_api "$api_app_id"

    web_app_id="$(ensure_app duora-web)"
    configure_web_client "$web_app_id" "$api_app_id"

    android_app_id="$(ensure_app duora-android)"
    configure_public_client "$android_app_id" "$api_app_id" "$(android_redirect_uris)"

    local api_sp
    api_sp="$(ensure_service_principal "$api_app_id")"
    grant_admin_consent "$web_app_id" "$api_sp" "openid profile"
    grant_admin_consent "$android_app_id" "$api_sp" "openid offline_access"

    link_apps_to_user_flow "$flow_id" "$web_app_id" "$android_app_id"
    ensure_web_client_secret "$web_app_id"
    write_dev_env "$api_app_id" "$web_app_id"

    print_settings "$api_app_id" "$web_app_id" "$android_app_id"
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

# Cliente confidencial: o Spring troca o código por tokens no servidor, autenticado pelo segredo.
configure_web_client() {
    local app_id="$1" api_app_id="$2" redirects
    redirects="$(web_redirect_uris)"
    graph PATCH "/applications(appId='$app_id')" "$(jq -n \
        --argjson redirects "$redirects" --arg api "$api_app_id" --arg scope "$API_SCOPE_ID" \
        --arg graph "$MICROSOFT_GRAPH_APP_ID" --arg openid "$GRAPH_SCOPE_OPENID" \
        --arg profile "$GRAPH_SCOPE_PROFILE" '{
        isFallbackPublicClient: false,
        web: {
            redirectUris: $redirects,
            implicitGrantSettings: { enableIdTokenIssuance: false, enableAccessTokenIssuance: false }
        },
        requiredResourceAccess: [
            { resourceAppId: $api, resourceAccess: [{ id: $scope, type: "Scope" }] },
            { resourceAppId: $graph, resourceAccess: [
                { id: $openid, type: "Scope" }, { id: $profile, type: "Scope" } ] }
        ]
    }')" >/dev/null
    echo "duora-web configurado" >&2
}

# Para cada origem: retorno do login e destino depois do logout no Entra.
web_redirect_uris() {
    local origin
    for origin in $WEB_ORIGINS; do
        printf '%s\n' "$origin/login/oauth2/code/entra" "$origin/"
    done | jq -R . | jq -s .
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

# Consentimento de administrador (todos os usuários) para o scope da API e os scopes OIDC do cliente.
grant_admin_consent() {
    local client_app_id="$1" api_sp="$2" oidc_scopes="$3" client_sp graph_sp
    client_sp="$(ensure_service_principal "$client_app_id")"
    graph_sp="$(ensure_service_principal "$MICROSOFT_GRAPH_APP_ID")"
    ensure_grant "$client_sp" "$api_sp" "access_as_user"
    ensure_grant "$client_sp" "$graph_sp" "$oidc_scopes"
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

# Imprime o id do fluxo de cadastro e login, criando-o se não existir.
ensure_user_flow() {
    local flow_id
    flow_id="$(graph GET "/identity/authenticationEventsFlows" \
        | jq -r --arg n "$USER_FLOW_NAME" '.value[] | select(.displayName == $n) | .id' | head -n 1)"
    if [[ -z "$flow_id" ]]; then
        flow_id="$(graph POST "/identity/authenticationEventsFlows" "$(user_flow_body)" | jq -r .id)"
        echo "Fluxo $USER_FLOW_NAME criado" >&2
    fi
    echo "$flow_id"
}

linked_apps() {
    graph GET "/identity/authenticationEventsFlows/$1/conditions/applications/includeApplications" | jq -r '.value[].appId'
}

link_apps_to_user_flow() {
    local flow_id="$1" linked app_id
    shift
    linked="$(linked_apps "$flow_id")"
    for app_id in "$@"; do
        if ! grep -qx "$app_id" <<<"$linked"; then
            graph POST "/identity/authenticationEventsFlows/$flow_id/conditions/applications/includeApplications" \
                "$(jq -n --arg a "$app_id" '{"@odata.type": "#microsoft.graph.authenticationConditionApplication", appId: $a}')" \
                >/dev/null
        fi
    done
}

# Desliga do fluxo e apaga registros que deixaram de existir; os consentimentos somem junto.
remove_obsolete_apps() {
    local flow_id="$1" name app_id
    for name in "${OBSOLETE_APPS[@]}"; do
        app_id="$(az ad app list --filter "displayName eq '$name'" --query "[0].appId" -o tsv)"
        [[ -z "$app_id" ]] && continue
        if grep -qx "$app_id" <<<"$(linked_apps "$flow_id")"; then
            graph DELETE "/identity/authenticationEventsFlows/$flow_id/conditions/applications/includeApplications/$app_id" \
                >/dev/null
        fi
        az ad app delete --id "$app_id"
        echo "Removido $name ($app_id)" >&2
    done
}

ensure_web_client_secret() {
    local app_id="$1" secret end_date
    if [[ -f "$DEV_ENV" ]] && grep -q '^DUORA_AUTH_WEB_CLIENT_SECRET=.' "$DEV_ENV"; then
        return
    fi
    end_date="$(date -u -v+"${WEB_SECRET_LIFETIME_DAYS}"d +%Y-%m-%dT%H:%M:%SZ)"
    secret="$(graph POST "/applications(appId='$app_id')/addPassword" "$(jq -n --arg end "$end_date" \
        '{passwordCredential: {displayName: "dev", endDateTime: $end}}')" | jq -r .secretText)"
    set_dev_env DUORA_AUTH_WEB_CLIENT_SECRET "$secret"
    echo "Segredo do duora-web criado (vence em $end_date) e gravado em $DEV_ENV" >&2
}

write_dev_env() {
    local api_app_id="$1" web_app_id="$2"
    set_dev_env DUORA_AUTH_ISSUER_URI "https://$TENANT_ID.ciamlogin.com/$TENANT_ID/v2.0"
    set_dev_env DUORA_AUTH_AUTHORITY "https://$TENANT_SUBDOMAIN.ciamlogin.com/$TENANT_ID"
    set_dev_env DUORA_AUTH_JWK_SET_URI "https://$TENANT_SUBDOMAIN.ciamlogin.com/$TENANT_ID/discovery/v2.0/keys"
    set_dev_env DUORA_AUTH_AUDIENCE "$api_app_id"
    set_dev_env DUORA_AUTH_WEB_CLIENT_ID "$web_app_id"
}

# Grava ou substitui uma linha CHAVE=valor no arquivo, que só o dono lê.
set_dev_env() {
    local key="$1" value="$2"
    mkdir -p "$(dirname "$DEV_ENV")"
    (umask 077 && touch "$DEV_ENV" && { grep -v "^$key=" "$DEV_ENV" || true; } > "$DEV_ENV.tmp")
    printf '%s=%s\n' "$key" "$value" >> "$DEV_ENV.tmp"
    mv "$DEV_ENV.tmp" "$DEV_ENV"
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
    local api_app_id="$1" web_app_id="$2" android_app_id="$3"
    printf '\nVariáveis da API gravadas em %s (o segredo não é exibido). Para usar:\n' "$DEV_ENV"
    printf '  set -a; source %s; set +a; ./mvnw spring-boot:run\n\n' "$DEV_ENV"
    printf 'Registros:\n'
    printf '  duora-api      %s (scope api://%s/access_as_user)\n' "$api_app_id" "$api_app_id"
    printf '  duora-web      %s (origens: %s)\n' "$web_app_id" "$WEB_ORIGINS"
    printf '  duora-android  %s, redirect %s\n' "$android_app_id" "$(android_redirect_uris | jq -r '.[0]')"
}

main "$@"
