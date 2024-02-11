FROM node:20-bookworm-slim

RUN apt-get update -y
RUN apt-get install -y bash
RUN apt-get install -y git
RUN apt-get install -y jq
RUN apt-get install -y curl
RUN apt-get install -y zip

# awscliv2
RUN curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
RUN unzip awscliv2.zip
RUN ./aws/install
RUN aws --version
RUN rm -rf ./aws
RUN rm awscliv2.zip

RUN npm install -g lerna@3.22.1
RUN npm install -g serverless@2.45
RUN npm install -g yarn@1.22.11
RUN npm install -g npm@8.5.3
RUN apt-get clean

RUN git config --global user.name 'sre'
RUN git config --global user.email 'sre@getcenter.com'