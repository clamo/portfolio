FROM node:20-bookworm-slim

RUN apt-get update -y
RUN apt-get install -y bash
RUN apt-get install -y curl
RUN apt-get install -y zip
RUN apt-get install -y git
RUN apt-get install -y tar
RUN apt-get install -y openssh-client
RUN apt-get install -y jq

# awscliv2
RUN curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
RUN unzip awscliv2.zip
RUN ./aws/install
RUN aws --version
RUN rm -rf ./aws
RUN rm awscliv2.zip

RUN apt-get install -y imagemagick
RUN apt-get clean
RUN npm install -g npm@8.5.3
RUN npm install -g mocha
RUN npm install -g typescript@4.9.3
RUN npm install -g gulp@4.0.2
COPY policy.xml /etc/ImageMagick-6/policy.xml

RUN git config --global user.name 'sre'
RUN git config --global user.email 'sre@domain.name'