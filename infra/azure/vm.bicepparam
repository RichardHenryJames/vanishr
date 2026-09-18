using './vm.bicep'

param adminUsername = 'vanishradmin'

param operator = {
  sshPublicKey: readEnvironmentVariable('VANISHR_SSH_PUBLIC_KEY')
  sourceIpv4: readEnvironmentVariable('VANISHR_OPERATOR_IPV4')
  notificationEmail: readEnvironmentVariable('VANISHR_BUDGET_EMAIL')
}

param dnsLabel = readEnvironmentVariable('VANISHR_DNS_LABEL')