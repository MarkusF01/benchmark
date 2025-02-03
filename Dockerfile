FROM maven:3-eclipse-temurin-11
WORKDIR /obm
COPY benchmark-framework/target ./benchmark-framework/target
COPY bin/ ./bin
RUN find /obm/bin -type f -exec sed -i 's/\r$//' {} +
RUN chmod +x /obm/bin/benchmark
CMD ["/bin/sh", "-c", "/obm/bin/benchmark --drivers /benchmark-config/kafka.yml /obm/workloads/simple-workload.yaml"]
