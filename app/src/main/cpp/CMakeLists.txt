cmake_minimum_required(VERSION 3.22.1)
project(ncnn_llm_jni)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(PROJECT_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../..")
set(NCNN_INCLUDE_DIR "${PROJECT_ROOT}/third_party/ncnn/include")
set(NCNN_LLM_SRC_DIR "${PROJECT_ROOT}/third_party/ncnn_llm/src")
set(NCNN_LLM_RUN_DIR "${PROJECT_ROOT}/third_party/ncnn_llm/examples/llm_ncnn_run")

file(GLOB NCNN_LLM_SRC
    "${NCNN_LLM_SRC_DIR}/*.cpp"
    "${NCNN_LLM_SRC_DIR}/utils/*.cpp"
    "${NCNN_LLM_SRC_DIR}/utils/tokenizer/*.cpp"
)

set(NCNN_LLM_RUN_SRC
    "${NCNN_LLM_RUN_DIR}/openai_server.cpp"
    "${NCNN_LLM_RUN_DIR}/json_utils.cpp"
    "${NCNN_LLM_RUN_DIR}/tools.cpp"
    "${NCNN_LLM_RUN_DIR}/android_tool_bridge.cpp"
    "${NCNN_LLM_RUN_DIR}/util.cpp"
    "${NCNN_LLM_RUN_DIR}/options.cpp"
    "${NCNN_LLM_RUN_DIR}/mcp.cpp"
    "${NCNN_LLM_RUN_DIR}/mcp_client.cpp"
)

add_library(ncnn SHARED IMPORTED)
set_target_properties(ncnn PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_LIST_DIR}/../jniLibs/${ANDROID_ABI}/libncnn.so"
)

add_library(ncnn_llm_jni SHARED
    ncnn_llm_jni.cpp
    ${NCNN_LLM_SRC}
    ${NCNN_LLM_RUN_SRC}
)

target_include_directories(ncnn_llm_jni PRIVATE
    "${NCNN_INCLUDE_DIR}"
    "${NCNN_LLM_SRC_DIR}"
    "${NCNN_LLM_SRC_DIR}/utils"
    "${NCNN_LLM_SRC_DIR}/utils/tokenizer"
    "${NCNN_LLM_RUN_DIR}"
    "${PROJECT_ROOT}/third_party"
    "${PROJECT_ROOT}/third_party/httplib"
)

target_compile_definitions(ncnn_llm_jni PRIVATE NCNN_LLM_NO_OPENCV)

target_link_libraries(ncnn_llm_jni
    ncnn
    log
    android
)

target_link_options(ncnn_llm_jni PRIVATE
    "-Wl,-z,common-page-size=16384"
    "-Wl,-z,max-page-size=16384"
)
