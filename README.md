# iac-pulumi
Fall 2023 CSYE6225 Network Structure &amp; Cloud Computing - iac-pulumi

## AWS Setup
1. 3 different AWS accounts are used for this project. dev, demo and root.
2. using pulumi to create the infrastructure.
   - using pulumi to create the vpc
   - using pulumi to create the subnets
   - using pulumi to create the internet gateway
   - using pulumi to create the route table
   - using pulumi to create the route table association

## SSL Certificate Import to AWS Certificate Manager CLI Command
```bash
aws acm import-certificate --certificate fileb://Certificate.pem \
      --certificate-chain fileb://ca_bundle.pem \
      --private-key fileb://private.key --region us-west-2
      
```

