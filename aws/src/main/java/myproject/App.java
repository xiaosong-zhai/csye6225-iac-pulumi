package myproject;

import com.pulumi.Context;
import com.pulumi.Pulumi;
import com.pulumi.aws.AwsFunctions;
import com.pulumi.aws.ec2.*;
import com.pulumi.aws.ec2.inputs.RouteTableRouteArgs;
import com.pulumi.aws.inputs.GetAvailabilityZonesArgs;

import java.util.*;

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
            // 打印每个可用区的名字
            zoneNames.forEach(zoneName -> System.out.println("Availability Zone: " + zoneName));
          } else {
            System.out.println(
                "No availability zones retrieved or there was an error during retrieval.");
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
                        .routeTableId(publicRouteTable.id()) // 引用公共路由表的 ID
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
                        .routeTableId(privateRouteTable.id()) // 引用私有路由表的 ID
                        .build());
          }
          return null;
        });
    }
}
