FROM docker:27-cli
RUN apk add --no-cache python3
WORKDIR /app
COPY server.py /app/server.py
EXPOSE 8091
ENTRYPOINT ["python3", "/app/server.py"]
