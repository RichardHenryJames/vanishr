targetScope = 'resourceGroup'

@secure()
type OperatorSettings = {
  sshPublicKey: string
  sourceIpv4: string
  notificationEmail: string
}

type DeploymentEndpoint = {
  vmName: string
  hostname: string
  publicIp: string
  adminUsername: string
  resourceGroup: string
}

@description('Operator access and budget notification settings. No private SSH key is accepted.')
param operator OperatorSettings

@description('Dedicated administrator used by the hardened host initialization.')
param adminUsername string

@description('Unique public DNS label for this isolated development deployment.')
@minLength(8)
@maxLength(40)
param dnsLabel string

@description('Start of the monthly budget period. This alert is not a hard spending cap.')
param budgetStartDate string = '${utcNow('yyyy-MM')}-01T00:00:00Z'

var location = 'centralindia'
var tags = {
  application: 'vanishr'
  environment: 'devtest'
  managedBy: 'vanishr-isolated-deployment'
  planningBudgetINR: '1200'
}

resource monthlyBudget 'Microsoft.Consumption/budgets@2023-11-01' = {
  name: 'vanishr-monthly'
  properties: {
    category: 'Cost'
    amount: 1200
    timeGrain: 'Monthly'
    timePeriod: {
      startDate: budgetStartDate
    }
    notifications: {
      halfUsed: {
        enabled: true
        operator: 'GreaterThanOrEqualTo'
        threshold: 50
        thresholdType: 'Actual'
        contactEmails: [operator.notificationEmail]
      }
      eightyUsed: {
        enabled: true
        operator: 'GreaterThanOrEqualTo'
        threshold: 80
        thresholdType: 'Actual'
        contactEmails: [operator.notificationEmail]
      }
      budgetUsed: {
        enabled: true
        operator: 'GreaterThanOrEqualTo'
        threshold: 100
        thresholdType: 'Actual'
        contactEmails: [operator.notificationEmail]
      }
    }
  }
}

resource networkSecurityGroup 'Microsoft.Network/networkSecurityGroups@2024-05-01' = {
  name: 'vanishr-nsg'
  location: location
  tags: tags
  properties: {
    securityRules: [
      {
        name: 'OperatorSshOnly'
        properties: {
          priority: 100
          direction: 'Inbound'
          access: 'Allow'
          protocol: 'Tcp'
          sourceAddressPrefix: '${operator.sourceIpv4}/32'
          sourcePortRange: '*'
          destinationAddressPrefix: '*'
          destinationPortRange: '22'
        }
      }
      {
        name: 'HttpsRelay'
        properties: {
          priority: 110
          direction: 'Inbound'
          access: 'Allow'
          protocol: 'Tcp'
          sourceAddressPrefix: 'Internet'
          sourcePortRange: '*'
          destinationAddressPrefix: '*'
          destinationPortRange: '443'
        }
      }
      {
        name: 'CertificateChallenge'
        properties: {
          priority: 120
          direction: 'Inbound'
          access: 'Allow'
          protocol: 'Tcp'
          sourceAddressPrefix: 'Internet'
          sourcePortRange: '*'
          destinationAddressPrefix: '*'
          destinationPortRange: '80'
        }
      }
    ]
  }
}

resource virtualNetwork 'Microsoft.Network/virtualNetworks@2024-05-01' = {
  name: 'vanishr-vnet'
  location: location
  tags: tags
  properties: {
    addressSpace: {
      addressPrefixes: ['10.77.0.0/24']
    }
  }
}

resource subnet 'Microsoft.Network/virtualNetworks/subnets@2024-05-01' = {
  parent: virtualNetwork
  name: 'relay'
  properties: {
    addressPrefix: '10.77.0.0/28'
    defaultOutboundAccess: false
  }
}

resource publicIp 'Microsoft.Network/publicIPAddresses@2024-05-01' = {
  name: 'vanishr-ip'
  location: location
  tags: tags
  sku: {
    name: 'Standard'
    tier: 'Regional'
  }
  properties: {
    publicIPAllocationMethod: 'Static'
    publicIPAddressVersion: 'IPv4'
    idleTimeoutInMinutes: 4
    dnsSettings: {
      domainNameLabel: dnsLabel
      domainNameLabelScope: 'ResourceGroupReuse'
    }
  }
  dependsOn: [monthlyBudget]
}

resource networkInterface 'Microsoft.Network/networkInterfaces@2024-05-01' = {
  name: 'vanishr-nic'
  location: location
  tags: tags
  properties: {
    networkSecurityGroup: {
      id: networkSecurityGroup.id
    }
    ipConfigurations: [
      {
        name: 'primary'
        properties: {
          privateIPAllocationMethod: 'Dynamic'
          subnet: {
            id: subnet.id
          }
          publicIPAddress: {
            id: publicIp.id
          }
        }
      }
    ]
  }
}

resource virtualMachine 'Microsoft.Compute/virtualMachines@2024-07-01' = {
  name: 'vanishr-dev'
  location: location
  tags: tags
  properties: {
    hardwareProfile: {
      vmSize: 'Standard_B2pts_v2'
    }
    storageProfile: {
      imageReference: {
        publisher: 'Canonical'
        offer: 'ubuntu-24_04-lts'
        sku: 'minimal-arm64'
        version: '24.04.202608270'
      }
      osDisk: {
        name: 'vanishr-os'
        createOption: 'FromImage'
        diskSizeGB: 32
        caching: 'ReadWrite'
        deleteOption: 'Delete'
        managedDisk: {
          storageAccountType: 'Standard_LRS'
        }
      }
    }
    osProfile: {
      computerName: 'vanishr-dev'
      adminUsername: adminUsername
      customData: base64(loadTextContent('cloud-init.yml'))
      linuxConfiguration: {
        disablePasswordAuthentication: true
        provisionVMAgent: true
        ssh: {
          publicKeys: [
            {
              path: '/home/${adminUsername}/.ssh/authorized_keys'
              keyData: operator.sshPublicKey
            }
          ]
        }
      }
    }
    networkProfile: {
      networkInterfaces: [
        {
          id: networkInterface.id
          properties: {
            primary: true
            deleteOption: 'Delete'
          }
        }
      ]
    }
    diagnosticsProfile: {
      bootDiagnostics: {
        enabled: false
      }
    }
  }
}

output endpoint DeploymentEndpoint = {
  vmName: virtualMachine.name
  hostname: publicIp.properties.?dnsSettings.?fqdn ?? ''
  publicIp: publicIp.properties.?ipAddress ?? ''
  adminUsername: adminUsername
  resourceGroup: resourceGroup().name
}