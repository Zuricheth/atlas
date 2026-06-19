package com.qianyu.atlas;

import com.qianyu.atlas.paper.PaperStorageProperties;
import com.qianyu.atlas.document.MineruProperties;
import com.qianyu.atlas.library.LibraryStorageProperties;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@MapperScan(basePackages = "com.qianyu.atlas", annotationClass = Mapper.class)
@EnableConfigurationProperties({PaperStorageProperties.class, LibraryStorageProperties.class, MineruProperties.class})
@SpringBootApplication
public class AtlasBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(AtlasBackendApplication.class, args);
	}

}
