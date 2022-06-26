FROM bellsoft/liberica-openjdk-alpine:11.0.15.1-2
RUN apk add --no-cache bash && mkdir app && cd app

WORKDIR app
COPY ./target/universal/stage .

ENTRYPOINT ["./bin/minimalzio"]
#ENTRYPOINT ["sleep", "infinity"]

