package myproject;

import com.pulumi.Context;
import com.pulumi.Pulumi;
import com.pulumi.aws.AwsFunctions;
import com.pulumi.aws.ec2.*;
import com.pulumi.aws.ec2.inputs.InstanceRootBlockDeviceArgs;
import com.pulumi.aws.ec2.inputs.RouteTableRouteArgs;
import com.pulumi.aws.ec2.inputs.SecurityGroupEgressArgs;
import com.pulumi.aws.ec2.inputs.SecurityGroupIngressArgs;
import com.pulumi.aws.inputs.GetAvailabilityZonesArgs;
import com.pulumi.aws.rds.ParameterGroup;
import com.pulumi.aws.rds.ParameterGroupArgs;
import com.pulumi.aws.rds.SubnetGroup;
import com.pulumi.aws.rds.SubnetGroupArgs;
import com.pulumi.aws.rds.inputs.ParameterGroupParameterArgs;
import com.pulumi.core.Output;

import java.util.*;
import java.util.stream.Collectors;

public class App {
    public static void main(String[] args) {
        Pulumi.run(App::stack);
    }

    public static void stack(Context ctx) {
        var config = ctx.config();

        /*
        create vpc
         */
        // get pulumi.dev.yaml config
        Optional<String> vpcTagNameKey = config.get("vpcTagNameKey");
        Optional<String> vpcTagNameValue = config.get("vpcTagNameValue");
        Optional<String> cidrBlockOpt = config.get("cidrBlock");
        // check config
        if(vpcTagNameKey.isEmpty() || vpcTagNameValue.isEmpty() || cidrBlockOpt.isEmpty()) {
            throw new RuntimeException("vpcName and cidrBlock must be configured");
        }
        // get config value to string
        String vpcName = vpcTagNameValue.get();
        String vpcTagName = vpcTagNameKey.get();
        String cidrBlock = cidrBlockOpt.get();
        // create vpc
        var main = new Vpc(vpcName, VpcArgs.builder()
                .cidrBlock(cidrBlock)
                .tags(Map.of(vpcTagName, vpcName))
                .build());

        /*
        create internet gateway
         */
        Optional<String> igwTagNameKey = config.get("igwTagNameKey");
        Optional<String> igwTagNameValue = config.get("igwTagNameValue");
        // check config
        if(igwTagNameKey.isEmpty() || igwTagNameValue.isEmpty()) {
            throw new RuntimeException("igwTagNameKey and igwTagNameValue must be configured");
        }
        // get config value to string
        String igwTagName = igwTagNameKey.get();
        String igwName = igwTagNameValue.get();
        var gw = new InternetGateway(igwName, InternetGatewayArgs.builder()
                .vpcId(main.id())
                .tags(Map.of(igwTagName, igwName))
                .build());

        /*
        create route table
         */
        Optional<String> routeTableTagNameKey = config.get("rtTagNameKey");
        Optional<String> publicRouteTableValue = config.get("publicRtValue");
        Optional<String> privateRouteTableValue = config.get("privateRtValue");
        // check config
        if(routeTableTagNameKey.isEmpty() || publicRouteTableValue.isEmpty() || privateRouteTableValue.isEmpty()) {
            throw new RuntimeException("routeTableTagNameKey, publicRtValue and privateRtValue must be configured");
        }
        // get config value to string
        String routeTableTagName = routeTableTagNameKey.get();
        String publicRtValue = publicRouteTableValue.get();
        String privateRtValue = privateRouteTableValue.get();
        var publicRouteTable = new RouteTable(publicRtValue, RouteTableArgs.builder()
                .vpcId(main.id())
                .routes(Collections.singletonList(
                        RouteTableRouteArgs.builder()
                                .gatewayId(gw.id())
                                .cidrBlock("0.0.0.0/0")
                                .build()
                ))
                .tags(Map.of(routeTableTagName, publicRtValue))
                .build());
        var privateRouteTable = new RouteTable(privateRtValue, RouteTableArgs.builder()
                .vpcId(main.id())
                .tags(Map.of(routeTableTagName, privateRtValue))
                .build());

        /*
        create subnet
        */
        Optional<String> subnetTagNameKey = config.get("subnetTagNameKey");
        Optional<String> publicSubnetName = config.get("publicSubnetTagNameValue");
        Optional<String> privateSubnetName = config.get("privateSubnetTagNameValue");
        // check config
        if(subnetTagNameKey.isEmpty() || publicSubnetName.isEmpty() || privateSubnetName.isEmpty()) {
            throw new RuntimeException("subnetTagNameKey, publicSubnetTagNameValue and privateSubnetTagNameValue must be configured");
        }
        // get config value to string
        String subnetTagName = subnetTagNameKey.get();
        String publicSubnetTagName = publicSubnetName.get();
        String privateSubnetTagName = privateSubnetName.get();

        // get availability zone in region
        final var azs = AwsFunctions.getAvailabilityZones(GetAvailabilityZonesArgs.builder().build());

    // set azs to list
    azs.applyValue(
        availabilityZones -> {
          List<String> zoneNames = availabilityZones.names();
          if (zoneNames != null) {
            // Print each AZ in the region to the console.
            zoneNames.forEach(zoneName -> System.out.println("Availability Zone: " + zoneName));
          } else {
            System.out.println(
                "No availability zones.");
          }

          assert zoneNames != null;
          int zoneCount = zoneNames.size();

          int zonesToUse = Math.min(zoneCount, 3);

          List<Subnet> publicSubnets = new ArrayList<>();
          List<Subnet> privateSubnets = new ArrayList<>();

          // get base ip
          String[] parts = cidrBlock.split("\\.");
          String baseIp = parts[0] + "." + parts[1] + ".";

          for (int i = 0; i < zonesToUse; i++) {

            String subnetCidrBlockPublic = baseIp + (i * 2) + ".0/24";
            String subnetCidrBlockPrivate = baseIp + (i * 2 + 1) + ".0/24";

            var publicSubnet =
                new Subnet(
                    publicSubnetTagName + i,
                    SubnetArgs.builder()
                        .vpcId(main.id())
                        .cidrBlock(subnetCidrBlockPublic)
                        .availabilityZone(zoneNames.get(i))
                        .tags(Map.of(subnetTagName, publicSubnetTagName + i))
                        .build());


            var privateSubnet =
                new Subnet(
                    privateSubnetTagName + i,
                    SubnetArgs.builder()
                        .vpcId(main.id())
                        .cidrBlock(subnetCidrBlockPrivate)
                        .availabilityZone(zoneNames.get(i))
                        .tags(Map.of(subnetTagName, privateSubnetTagName + i))
                        .build());

            publicSubnets.add(publicSubnet);
            privateSubnets.add(privateSubnet);
          }

          // create public route table association
          for (int i = 0; i < publicSubnets.size(); i++) {
            Subnet publicSubnet = publicSubnets.get(i);
            var publicSubnetAssociation =
                new RouteTableAssociation(
                    "publicSubnetAssociation" + i,
                    RouteTableAssociationArgs.builder()
                        .subnetId(publicSubnet.id())
                        .routeTableId(publicRouteTable.id())
                        .build());
          }
          // create private route table association
          for (int i = 0; i < privateSubnets.size(); i++) {
            Subnet privateSubnet = privateSubnets.get(i);
            var privateSubnetAssociation =
                new RouteTableAssociation(
                    "privateSubnetAssociation" + i,
                    RouteTableAssociationArgs.builder()
                        .subnetId(privateSubnet.id())
                        .routeTableId(privateRouteTable.id())
                        .build());
          }
            // create a security group
            Optional<String> securityGroupTagNameKey = config.get("sgTagNameKey");
            Optional<String> securityGroupTagNameValue = config.get("sgTagNameValue");
            // check config
            if(securityGroupTagNameKey.isEmpty() || securityGroupTagNameValue.isEmpty()) {
                throw new RuntimeException("sgTagNameKey and sgTagNameValue must be configured");
            }
            // get config value to string
            String sgTagName = securityGroupTagNameKey.get();
            String sgTagNameValue = securityGroupTagNameValue.get();
            var appSecurityGroup = new SecurityGroup(sgTagNameValue, SecurityGroupArgs.builder()
                    .vpcId(main.id())
                    .description("Security group for web application")
                    .ingress(Arrays.asList(
                            SecurityGroupIngressArgs.builder()
                                    .description("SSH")
                                    .fromPort(22)
                                    .toPort(22)
                                    .protocol("tcp")
                                    .cidrBlocks("0.0.0.0/0")
                                    .build(),
                            SecurityGroupIngressArgs.builder()
                                    .description("HTTP")
                                    .fromPort(80)
                                    .toPort(80)
                                    .protocol("tcp")
                                    .cidrBlocks("0.0.0.0/0")
                                    .build(),
                            SecurityGroupIngressArgs.builder()
                                    .description("HTTPS")
                                    .fromPort(443)
                                    .toPort(443)
                                    .protocol("tcp")
                                    .cidrBlocks("0.0.0.0/0")
                                    .build(),
                            SecurityGroupIngressArgs.builder()
                                    .description("webapp")
                                    .fromPort(8080)  // replace with your application port
                                    .toPort(8080)  // replace with your application port
                                    .protocol("tcp")
                                    .cidrBlocks("0.0.0.0/0")
                                    .build()
                    ))
                    .egress(SecurityGroupEgressArgs.builder()
                            .fromPort(0)
                            .toPort(0)
                            .protocol("-1")
                            .cidrBlocks("0.0.0.0/0")
                            .ipv6CidrBlocks("::/0")
                            .build())
                    .tags(Map.of(sgTagName, sgTagNameValue))
                    .build());

            Output<String> sgId = appSecurityGroup.id();

            Optional<String> AMiId = config.get("amiId");
            // check config
            if(AMiId.isEmpty()) {
                throw new RuntimeException("amiId must be configured");
            }
            // get config value to string
            String amiId = AMiId.get();

            sgId.applyValue(id -> {
                // create a database security group
                Optional<String> dbSecurityGroupTagNameValue = config.get("dbSgTagNameValue");
                // check config
                if(dbSecurityGroupTagNameValue.isEmpty()) {
                    throw new RuntimeException("dbSgTagNameValue must be configured");
                }
                // get config value to string
                String dbSgTagNameValue = dbSecurityGroupTagNameValue.get();
                // create a security group
                var dbSecurityGroup = new SecurityGroup(dbSgTagNameValue, SecurityGroupArgs.builder()
                        .vpcId(main.id())
                        .description("Security group for database")
                        .ingress(Arrays.asList(
                                SecurityGroupIngressArgs.builder()
                                        .description("MariaDB")
                                        .fromPort(3306)
                                        .toPort(3306)
                                        .protocol("tcp")
                                        .securityGroups(appSecurityGroup.id().applyValue(List::of))
                                        .build()
                        ))
                        .build());
                var SecurityGroupRule = new SecurityGroupRule("dbSecurityGroupRule", SecurityGroupRuleArgs.builder()
                        .type("egress")
                        .fromPort(3306)
                        .toPort(3306)
                        .protocol("tcp")
                        .securityGroupId(appSecurityGroup.id())
                        .sourceSecurityGroupId(dbSecurityGroup.id())
                        .build());

                // create a parameter group
                Optional<String> dbParameterGroupName = config.get("dbParameterGroupName");
                Optional<String> dbParameterGroupFamily = config.get("dbParameterGroupFamily");
                // check config
                if(dbParameterGroupName.isEmpty() || dbParameterGroupFamily.isEmpty()) {
                    throw new RuntimeException("dbParameterGroupName and dbParameterGroupFamily must be configured");
                }
                // get config value to string
                String dbParameterGroupNameValue = dbParameterGroupName.get();
                String dbParameterGroupFamilyValue = dbParameterGroupFamily.get();
                // create a parameter group
                var dbParameterGroup = new ParameterGroup(dbParameterGroupNameValue, ParameterGroupArgs.builder()
                        .family(dbParameterGroupFamilyValue)
                        .description("Parameter group for MariaDB")
                        .parameters(Arrays.asList(
                                new ParameterGroupParameterArgs.Builder()
                                        .name("max_connections")
                                        .value("100")
                                        .applyMethod("immediate")
                                        .build(),
                                new ParameterGroupParameterArgs.Builder()
                                        .name("query_cache_size")
                                        .value("67108864")  // 64MB in bytes
                                        .applyMethod("immediate")
                                        .build(),
                                new ParameterGroupParameterArgs.Builder()
                                        .name("innodb_buffer_pool_size")
                                        .value("134217728")  // 128MB in bytes
                                        .applyMethod("immediate")
                                        .build()

                        ))
                        .build());
                // create a mariaDB instance
                Optional<String> dbTagNameKey = config.get("dbTagNameKey");
                Optional<String> dbTagNameValue = config.get("dbTagNameValue");
                Optional<String> dbInstanceName = config.get("dbInstanceName");
                Optional<String> dbInstanceClass = config.get("dbInstanceClass");
                Optional<String> dbMasterUsername = config.get("dbMasterUsername");
                Optional<String> dbMasterPassword = config.get("dbMasterPassword");
                // check config
                if(dbTagNameKey.isEmpty() || dbTagNameValue.isEmpty() || dbInstanceName.isEmpty() || dbInstanceClass.isEmpty() || dbMasterUsername.isEmpty() || dbMasterPassword.isEmpty()) {
                    throw new RuntimeException("dbTagNameKey, dbTagNameValue, dbInstanceName, dbInstanceClass, dbMasterUsername and dbMasterPassword must be configured");
                }
                // get config value to string
                String dbTagNameKeyString = dbTagNameKey.get();
                String dbTagNameValueString = dbTagNameValue.get();
                String dbInstanceNameString = dbInstanceName.get();
                String dbInstanceClassString = dbInstanceClass.get();
                String dbMasterUsernameString = dbMasterUsername.get();
                String dbMasterPasswordString = dbMasterPassword.get();

                // create a private subnet group
                var privateSubnetIds = Output.all(privateSubnets.stream().map(Subnet::id).collect(Collectors.toList()));

                var dbPrivateSubnetGroup = new SubnetGroup("db_private_subnet_group", SubnetGroupArgs.builder()
                        .subnetIds(privateSubnetIds.applyValue(ids -> ids))
                        .build()
                );


                // create a mariaDB instance
                var dbInstance = new com.pulumi.aws.rds.Instance(dbInstanceNameString, com.pulumi.aws.rds.InstanceArgs.builder()
                        .engine("mariadb")
                        .engineVersion("10.4.31")
                        .instanceClass(dbInstanceClassString)
                        .allocatedStorage(20)
                        .dbSubnetGroupName(dbPrivateSubnetGroup.name())
                        .vpcSecurityGroupIds(dbSecurityGroup.id().applyValue(List::of))
                        .parameterGroupName(dbParameterGroup.name())
                        .username(dbMasterUsernameString)
                        .password(dbMasterPasswordString)
                        .skipFinalSnapshot(true)
                        .publiclyAccessible(false)
                        .dbName(dbInstanceNameString)
                        .multiAz(false)
                        .tags(Map.of(dbTagNameKeyString, dbTagNameValueString))
                        .build());

                dbInstance.address().apply(address -> {
                    // create UserData script
                    String userData = String.join("\n",
                            "#!/bin/bash",
                            "sudo groupadd csye6225",
                            "sudo useradd -s /bin/false -g csye6225 -d /opt/csye6225 -m csye6225",
                            "cat > /opt/csye6225/application-demo.yml <<EOL",
                            "server:",
                            "  port: 8080",
                            "spring:",
                            "  application:",
                            "    name: csye6225",
                            "  profiles:",
                            "    active: demo",
                            "  main:",
                            "    allow-circular-references: true",
                            "  datasource:",
                            "    driver-class-name: org.mariadb.jdbc.Driver",
                            "    url: jdbc:mariadb://"+ address +":3306/csye6225?createDatabaseIfNotExist=true",
                            "    username: " + dbMasterUsernameString,
                            "    password: " + dbMasterPasswordString,
                            "  jpa:",
                            "    hibernate:",
                            "      ddl-auto: update",
                            "    properties:",
                            "      hibernate:",
                            "        show_sql: true",
                            "        format_sql: true",
                            "        dialect: org.hibernate.dialect.MariaDBDialect",
                            "    database-platform: org.hibernate.dialect.MariaDBDialect",
                            "csv:",
                            "  file:",
                            "    # path: \"classpath:static/users.csv\"",
                            "    path: \"file:/opt/csye6225/users.csv\"",
                            "EOL",
                            "sudo mv /opt/webapp.jar /opt/csye6225/webapp.jar",
                            "sudo mv /opt/users.csv /opt/csye6225/users.csv",
                            "sudo chown csye6225:csye6225 /opt/csye6225/webapp.jar",
                            "sudo chown csye6225:csye6225 /opt/csye6225/users.csv",
                            "sudo chown csye6225:csye6225 /opt/csye6225/application-demo.yml",
                            "sudo systemctl enable /etc/systemd/system/csye6225.service",
                            "sudo systemctl start csye6225.service"
                    );


                    List<String> securityGroups = Collections.singletonList(id);
                    var webappInstance = new Instance("webapp", InstanceArgs.builder()
                            .instanceType("t2.micro")
                            .vpcSecurityGroupIds(securityGroups)
                            .ami(amiId)
                            .subnetId(publicSubnets.get(0).id())
                            .associatePublicIpAddress(true)
                            .disableApiTermination(false)
                            .keyName("test")
                            .userData(userData)
                            .rootBlockDevice(InstanceRootBlockDeviceArgs.builder()
                                    .volumeSize(25)
                                    .volumeType("gp2")
                                    .deleteOnTermination(true)
                                    .build())
                            .tags(Map.of("Name", "webapp"))
                            .build());

                    return Output.ofNullable(null);
                });
                return null;
            });
            return null;
        });

    }
}
